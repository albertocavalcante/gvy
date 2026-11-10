package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseOutcome
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The last confident SPECIFICATION verdicts per open document, read only by the table action (#1387).
 *
 * A spec proven by resolution loses its verdict while a base it extends fails to compile: gls excludes
 * the failing file, the base becomes a stub, and the class is UNCERTAIN. A clean compile records each
 * confident spec with its declaration and the bases it was proven through. A degraded compile (excluded
 * sources, stubbed classes or a CONVERSION fallback) recalls a spec only when all of these hold:
 * - the compile fingerprint is unchanged;
 * - the declaration is unchanged: for the class and each enclosing class, the superclass and interface
 *   spellings as written (`ClassNode.unresolvedName`), plus the module's imports;
 * - the degraded part is the same base: one of its source files is excluded, one of its classes is stubbed,
 *   or, for a base from a jar, the whole compile fell back;
 * - every in-workspace base's prelude (its significant tokens from the file start to the class body,
 *   covering package, imports, annotations and the `extends` clause, found with the Groovy lexer so comments
 *   and strings are ignored) still hashes to what the clean compile saw. A prelude that cannot be computed
 *   on either side denies reuse; the check can only deny. It catches a base edited to stop extending
 *   `Specification` and then broken, whether or not the table action ran in between.
 *
 * The record is replaced by the next clean compile the table action sees, recomputed only when the compiled
 * AST changes, and dropped when the document closes; a write from a request that started before the close
 * is discarded. Only strings and hashes are kept, never AST nodes beyond a weak reference to the last module.
 *
 * Known limits: the recalled verdict assumes the broken base still extends what its prelude says, so an edit
 * that changes spec identity outside the prelude, for example through a base's own base in a jar, is not
 * seen until the base compiles again. The compile result does not keep other sources' text, so the base is
 * read when the record is written rather than when it was compiled; a prelude whose `extends` disagrees
 * with the compiled AST is not recorded (and so denies), but an edit elsewhere in the prelude between the
 * compile and the record is hashed as if compiled. That is acceptable for a whitespace-only, reversible alignment;
 * nothing else reads this memory.
 */
internal class SpockVerdictMemory {
    private class Base(val source: String?, val name: String, val prelude: String?)

    private class Remembered(val declaration: String, val bases: List<Base>, val jvmBase: Boolean)

    private class Record(
        val fingerprint: String,
        val module: WeakReference<ModuleNode>,
        val classes: Map<String, Remembered>,
    )

    private val lock = Any()
    private val records = HashMap<String, Record>()
    private val epochs = HashMap<String, Long>()

    /** Starts one request for [uri]: [readSource] reads a source's current text by its normalized path. */
    fun access(uri: String, fingerprint: String, outcome: ParseOutcome, readSource: (String) -> String?): Access =
        Access(uri, fingerprint, outcome, readSource, synchronized(lock) { epochs[uri] ?: 0L })

    fun forget(uri: String) {
        synchronized(lock) {
            records.remove(uri)
            epochs[uri] = (epochs[uri] ?: 0L) + 1
        }
    }

    /** One request's view: its document, compile fingerprint, parse outcome and close epoch. */
    inner class Access internal constructor(
        private val uri: String,
        private val fingerprint: String,
        private val outcome: ParseOutcome,
        private val readSource: (String) -> String?,
        private val epoch: Long,
    ) {
        /**
         * A clean compile refreshes the record and recalls nothing. A degraded compile returns the names of
         * UNCERTAIN classes whose remembered SPECIFICATION still applies (see the class docs).
         */
        fun exchange(module: ModuleNode, source: SpockSourceContext.Available): Set<String> {
            if (!isDegraded(outcome)) {
                refresh(module, source)
                return emptySet()
            }
            val record = synchronized(lock) { records[uri] }?.takeIf { it.fingerprint == fingerprint }
                ?: return emptySet()
            val imports = importsOf(module)
            return source.classes.filter { it.status == SpecificationStatus.UNCERTAIN }
                .mapNotNullTo(HashSet()) { context ->
                    val remembered = record.classes[context.classNode.name]
                    context.classNode.name.takeIf {
                        remembered != null && remembered.declaration == declaration(context.classNode, imports) &&
                            sameBaseDegraded(remembered) && preludesUnchanged(remembered)
                    }
                }
        }

        private fun refresh(module: ModuleNode, source: SpockSourceContext.Available) {
            val current = synchronized(lock) { records[uri] }
            if (current?.fingerprint == fingerprint && current.module.get() === module) return
            val imports = importsOf(module)
            val classes = source.classes.filter { it.status == SpecificationStatus.SPECIFICATION }
                .associate { context ->
                    val chain = chain(context.classNode, module)
                    context.classNode.name to
                        Remembered(declaration(context.classNode, imports), chain.bases, chain.jvm)
                }
            synchronized(lock) {
                if ((epochs[uri] ?: 0L) == epoch) records[uri] = Record(fingerprint, WeakReference(module), classes)
            }
        }

        private fun sameBaseDegraded(remembered: Remembered): Boolean {
            val excluded = outcome.excludedSources.mapTo(HashSet()) { sourceKey(it.source) }
            return remembered.bases.any { it.source in excluded || it.name in outcome.stubbedClasses } ||
                (remembered.jvmBase && outcome.fallback != null)
        }

        /** A prelude that cannot be computed on either side denies reuse. */
        private fun preludesUnchanged(remembered: Remembered): Boolean = remembered.bases.all { base ->
            val current = base.source?.let(readSource)?.let { preludeOf(it, base.name)?.hash }
            base.source == null || (base.prelude != null && base.prelude == current)
        }

        private fun chain(node: ClassNode, module: ModuleNode): Chain {
            val bases = mutableListOf<Base>()
            val seen: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())
            var link = node.superClass?.redirect()
            var jvm = false
            // Plain properties and a visited set only: Groovy's hierarchy helpers loop on a cycle (#1369).
            while (link != null && !jvm && seen.add(link)) {
                when {
                    link.name == ClassHelper.OBJECT_TYPE.name -> link = null
                    link.isResolved -> jvm = true
                    else -> {
                        val sourceKey = link.module?.takeIf { it !== module }?.context?.name?.let(::sourceKey)
                        // The text is read now, not when the compile ran: keep it only if its `extends` still
                        // names the superclass this compile saw, so an edit in between cannot be hashed in.
                        val written = link.getUnresolvedSuperClass(false)?.unresolvedName
                        val prelude = sourceKey?.let(readSource)?.let { preludeOf(it, link.name) }
                            ?.takeIf { it.extendsMatches(written) }?.hash
                        bases += Base(sourceKey, link.name, prelude)
                        link = link.superClass?.redirect()
                    }
                }
            }
            return Chain(bases, jvm)
        }
    }

    private class Chain(val bases: List<Base>, val jvm: Boolean)

    /** A base's prelude hash and the `extends` name its text declares (null for none). */
    class Prelude(val hash: String, val extendsName: String?) {
        /** Whether the text agrees with the compiled AST's written superclass; no `extends` means Object. */
        fun extendsMatches(written: String?): Boolean =
            if (extendsName == null) written == null || written in OBJECT_SPELLINGS else extendsName == written
    }

    /** Header scan state: keyword, then the class name, then `extends` up to `implements` or the body. */
    private data class PreludeScan(
        val keyword: Boolean = false,
        val inHeader: Boolean = false,
        val inExtends: Boolean = false,
        val extendsName: String? = null,
    ) {
        fun next(token: Token, simple: String): PreludeScan = when {
            !inHeader && keyword -> copy(keyword = false, inHeader = token.text == simple)
            !inHeader -> copy(keyword = token.type in DECLARATION_KEYWORDS)
            token.type == GroovyLangLexer.EXTENDS -> copy(inExtends = true, extendsName = "")
            inExtends && token.type in NAME_TOKENS -> copy(extendsName = extendsName + token.text)
            else -> copy(inExtends = false)
        }
    }

    private companion object {
        val DECLARATION_KEYWORDS = setOf(
            GroovyLangLexer.CLASS,
            GroovyLangLexer.INTERFACE,
            GroovyLangLexer.TRAIT,
            GroovyLangLexer.ENUM,
        )
        val NAME_TOKENS = setOf(GroovyLangLexer.Identifier, GroovyLangLexer.CapitalizedIdentifier, GroovyLangLexer.DOT)
        val OBJECT_SPELLINGS = setOf("java.lang.Object", "Object")
        const val MAX_SOURCE_CHARS = 1_000_000

        fun isDegraded(outcome: ParseOutcome): Boolean =
            outcome.fallback != null || outcome.excludedSources.isNotEmpty() || outcome.stubbedClasses.isNotEmpty()

        fun importsOf(module: ModuleNode): String = (
            module.imports.map { "${it.className} as ${it.alias}" } + module.starImports.map { "${it.packageName}*" }
            ).joinToString(";")

        /** Superclass and interface spellings as written, for [node] and each enclosing class, plus [imports]. */
        fun declaration(node: ClassNode, imports: String): String = generateSequence(node) { it.outerClass }
            .joinToString("|") { type ->
                val superclass = type.getUnresolvedSuperClass(false)?.unresolvedName
                val interfaces = type.getUnresolvedInterfaces(false).orEmpty().joinToString(",") { it.unresolvedName }
                "${type.name}:$superclass:$interfaces"
            } + "|" + imports

        /**
         * The significant tokens of [text] up to the body of the class named [fqn], hashed, with the class's
         * written `extends` name; null when the declaration is not found or the text does not lex. Comments and
         * string literals are not significant, so they can neither end the prelude nor change the hash. Tokens
         * are pulled lazily and the scan stops at the body's `{`, so a broken body is never lexed.
         */
        fun preludeOf(text: String, fqn: String): Prelude? {
            if (text.length > MAX_SOURCE_CHARS) return null
            val simple = fqn.substringAfterLast('.').substringAfterLast('$')
            return runCatching { scanPrelude(GroovyLangLexer(CharStreams.fromString(text)), simple) }
                .onFailure { failure -> if (failure is Error && failure !is GroovySyntaxError) throw failure }
                .getOrNull()
        }

        private fun scanPrelude(lexer: GroovyLangLexer, simple: String): Prelude? {
            val significant = mutableListOf<String>()
            var state = PreludeScan()
            var token = lexer.nextToken()
            while (token.type != Token.EOF) {
                if (token.channel == Token.DEFAULT_CHANNEL && token.type != GroovyLangLexer.NL) {
                    if (state.inHeader && token.type == GroovyLangLexer.LBRACE) {
                        return Prelude(sha256(significant.joinToString(" ")), state.extendsName)
                    }
                    significant += token.text
                    state = state.next(token, simple)
                }
                token = lexer.nextToken()
            }
            return null
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Source unit names and excluded-source locators as normalized paths (`file:` URLs or plain paths). */
        fun sourceKey(name: String): String = runCatching {
            val path = if (name.startsWith("file:")) Path.of(URI(name)) else Path.of(name)
            path.toAbsolutePath().normalize().toString()
        }.getOrDefault(name)
    }
}
