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
 * - every link above the class is unchanged. A link in the spec's own file must have the same declaration
 *   in the current module. A link in another file must have the same prelude (the file's package and imports,
 *   each enclosing class's header and its own header, found with the Groovy lexer and matched by enclosing
 *   class, so edits to unrelated classes in the file do not count and comments, strings and same-named nested
 *   classes cannot stand in; plus every class name declared in the file, since a new class there can shadow a
 *   star-imported name) and the same package file set across every source
 *   root, since a new file in that package, even under another root, can shadow a star-imported name.
 *   Anything that cannot be computed denies reuse; these checks can only deny. They catch a base edited to
 *   stop extending `Specification` and then broken, whether or not the table action ran in between.
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
 * compile and the record is hashed as if compiled. When a base's file stops lexing after the base's header,
 * only the class names before that point are known: a class added after the lex error, say one shadowing
 * `Specification`, cannot deny reuse until the file lexes again, so the recall may disagree with what the
 * fixed file will mean. That is acceptable for a whitespace-only, reversible alignment; nothing else reads
 * this memory.
 */
internal class SpockVerdictMemory {
    /**
     * One link above a remembered spec. A link in another file keeps its source, prelude hash and package
     * file set; a link declared in the spec's own file keeps its declaration instead.
     */
    private class Base(
        val source: String?,
        val name: String,
        val prelude: String?,
        val packageFiles: String?,
        val localDeclaration: String?,
        /** The base's package from the clean compile's AST; the broken base has no AST at recall. */
        val packageName: String = "",
    )

    private class Remembered(val declaration: String, val bases: List<Base>, val jvmBase: Boolean)

    private class Record(
        val fingerprint: String,
        val module: WeakReference<ModuleNode>,
        val classes: Map<String, Remembered>,
    )

    private val lock = Any()
    private val records = HashMap<String, Record>()
    private val epochs = HashMap<String, Long>()
    private val decisions = HashMap<String, String>()

    /** Why the last degraded request for [uri] recalled or denied each class; for diagnostics and tests. */
    internal fun lastDecision(uri: String): String? = synchronized(lock) { decisions[uri] }

    /**
     * Starts one request for [uri]. By a source's normalized path, [readSource] reads its current text and
     * [listPackage] the current file set of its package (named as in the clean compile's AST); null from either
     * denies reuse.
     */
    fun access(
        uri: String,
        fingerprint: String,
        outcome: ParseOutcome,
        readSource: (String) -> String?,
        listPackage: (source: String, packageName: String) -> String?,
    ): Access = Access(uri, fingerprint, outcome, readSource, listPackage, synchronized(lock) { epochs[uri] ?: 0L })

    fun forget(uri: String) {
        synchronized(lock) {
            records.remove(uri)
            decisions.remove(uri)
            epochs[uri] = (epochs[uri] ?: 0L) + 1
        }
    }

    /** One request's view: its document, compile fingerprint, parse outcome and close epoch. */
    inner class Access internal constructor(
        private val uri: String,
        private val fingerprint: String,
        private val outcome: ParseOutcome,
        private val readSource: (String) -> String?,
        private val listPackage: (source: String, packageName: String) -> String?,
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
            val record = synchronized(lock) { records[uri] }
            if (record == null || record.fingerprint != fingerprint) {
                decide(if (record == null) "no record" else "fingerprint changed")
                return emptySet()
            }
            val imports = importsOf(module)
            val verdicts = source.classes.filter { it.status == SpecificationStatus.UNCERTAIN }
                .associate { context -> context.classNode.name to denial(record, context.classNode, module, imports) }
            decide(verdicts.entries.joinToString("; ") { (name, denial) -> "$name: ${denial ?: "recalled"}" })
            return verdicts.filterValues { it == null }.keys
        }

        /** Null when [node]'s remembered SPECIFICATION applies, else the first check that denies it. */
        private fun denial(record: Record, node: ClassNode, module: ModuleNode, imports: String): String? {
            val remembered = record.classes[node.name] ?: return "not remembered (recorded: ${record.classes.keys})"
            val current = declaration(node, imports)
            return when {
                remembered.declaration != current ->
                    "declaration differs: recorded=[${remembered.declaration}] current=[$current]"
                !sameBaseDegraded(remembered) ->
                    "base not degraded: bases=${remembered.bases.map { "${it.name}@${it.source}" }} " +
                        "excluded=${outcome.excludedSources.map { it.source }} stubbed=${outcome.stubbedClasses}"
                else -> baseDifference(remembered, module, imports)
            }
        }

        private fun decide(text: String) {
            synchronized(lock) { if ((epochs[uri] ?: 0L) == epoch) decisions[uri] = text }
        }

        private fun refresh(module: ModuleNode, source: SpockSourceContext.Available) {
            val current = synchronized(lock) { records[uri] }
            if (current?.fingerprint == fingerprint && current.module.get() === module) return
            val imports = importsOf(module)
            val classes = source.classes.filter { it.status == SpecificationStatus.SPECIFICATION }
                .associate { context ->
                    val chain = chain(context.classNode, module, imports)
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

        /**
         * Every link must still be what the clean compile saw. An in-file link compares its declaration in the
         * current module; a link in another file compares its prelude hash and its package's file set, since a
         * new file there can shadow a star-imported name. Anything that cannot be computed denies reuse.
         */
        /** Null when every remembered base is unchanged, else what changed for the first one that did. */
        private fun baseDifference(remembered: Remembered, module: ModuleNode, imports: String): String? =
            remembered.bases.firstNotNullOfOrNull { base ->
                if (base.source == null) {
                    val current = module.classes.firstOrNull { it.name == base.name }?.let { declaration(it, imports) }
                    "local base ${base.name}: recorded=[${base.localDeclaration}] current=[$current]"
                        .takeUnless { base.localDeclaration != null && base.localDeclaration == current }
                } else {
                    val prelude = readSource(base.source)?.let { preludeOf(it, base.name)?.hash }
                    val files = listPackage(base.source, base.packageName)
                    when {
                        base.prelude == null || base.prelude != prelude ->
                            "prelude of ${base.name}: recorded=${base.prelude} current=$prelude"
                        base.packageFiles == null || base.packageFiles != files ->
                            "package files of ${base.name}: recorded=${base.packageFiles} current=$files"
                        else -> null
                    }
                }
            }

        private fun chain(node: ClassNode, module: ModuleNode, imports: String): Chain {
            val bases = mutableListOf<Base>()
            val seen: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())
            var link = node.superClass?.redirect()
            var jvm = false
            // Plain properties and a visited set only: Groovy's hierarchy helpers loop on a cycle (#1369).
            while (link != null && !jvm && seen.add(link)) {
                when {
                    link.name == ClassHelper.OBJECT_TYPE.name -> link = null
                    link.isResolved -> jvm = true
                    link.module === module -> {
                        bases += Base(null, link.name, null, null, declaration(link, imports))
                        link = link.superClass?.redirect()
                    }
                    else -> {
                        val sourceKey = link.module?.context?.name?.let(::sourceKey)
                        // The text is read now, not when the compile ran: keep it only if its `extends` still
                        // names the superclass this compile saw, so an edit in between cannot be hashed in.
                        val written = link.getUnresolvedSuperClass(false)?.unresolvedName
                        val prelude = sourceKey?.let(readSource)?.let { preludeOf(it, link.name) }
                            ?.takeIf { it.extendsMatches(written) }?.hash
                        val packageName = link.packageName.orEmpty()
                        val files = sourceKey?.let { listPackage(it, packageName) }
                        bases += Base(sourceKey, link.name, prelude, files, null, packageName)
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

    /**
     * Finds the header of the class whose nested [path] (outermost first) is the target: a declaration keyword
     * and its name, matched only directly inside the path's enclosing classes, or at depth 0 for a top-level
     * class, so a same-named class elsewhere in the file cannot stand in for it. Then collects the written
     * `extends` name up to `implements` or the body.
     */
    private class PreludeScanner(private val path: List<String>) {
        /** Classes whose bodies are open: name, the depth outside their body, and their header tokens. */
        private val enclosing = ArrayDeque<Triple<String, Int, String>>()
        private val seen = mutableListOf<String>()
        private val segment = mutableListOf<String>()
        private var filePrelude: List<String>? = null
        private var depth = 0
        private var keyword = false
        private var afterDot = false
        private var pendingClass: String? = null
        private var inHeader = false
        private var inExtends = false
        var extendsName: String? = null
            private set

        /** Every class name declared in the file, top-level and nested, wherever it appears. */
        val declaredNames = mutableListOf<String>()

        /**
         * What identifies the target: the file's tokens before its first top-level declaration (package,
         * imports), each enclosing class's header, and the target's header. Bodies and unrelated headers are left
         * out, so an edit elsewhere in the file does not change it; declared class names are added separately.
         */
        val identity: String
            get() {
                val parts = listOf(filePrelude.orEmpty().joinToString(" ")) + enclosing.map { it.third } +
                    segment.joinToString(" ")
                return parts.joinToString(" | ")
            }

        /** Feeds one significant token; true when it is the target's body `{`. */
        fun accept(token: Token): Boolean {
            val wasAfterDot = afterDot
            afterDot = token.type == GroovyLangLexer.DOT
            if (inHeader) {
                if (token.type == GroovyLangLexer.LBRACE) return true
                segment += token.text
                header(token)
                return false
            }
            when (token.type) {
                GroovyLangLexer.LBRACE -> open()
                GroovyLangLexer.RBRACE -> close()
                else -> {
                    segment += token.text
                    outside(token, wasAfterDot)
                }
            }
            seen += token.text
            return false
        }

        private fun outside(token: Token, wasAfterDot: Boolean) {
            when {
                keyword -> declare(token.text)
                // `Foo.class` is a class literal, not a declaration.
                token.type in DECLARATION_KEYWORDS && !wasAfterDot -> {
                    keyword = true
                    if (depth == 0 && filePrelude == null) filePrelude = seen.toList()
                }
            }
        }

        private fun declare(name: String) {
            keyword = false
            declaredNames += name
            val directlyInside = depth == (enclosing.lastOrNull()?.second?.plus(1) ?: 0)
            inHeader = name == path.last() && directlyInside && enclosing.map { it.first } == path.dropLast(1)
            pendingClass = name.takeUnless { inHeader }
        }

        private fun open() {
            pendingClass?.let { enclosing.addLast(Triple(it, depth, segment.joinToString(" "))) }
            pendingClass = null
            segment.clear()
            depth++
        }

        private fun close() {
            depth--
            if (enclosing.lastOrNull()?.second == depth) enclosing.removeLast()
            segment.clear()
        }

        /**
         * After the target's header: only collect declared names, so a class added after the target that
         * shadows a name (for example `Specification` under a star import) still changes the identity.
         */
        fun collectName(token: Token) {
            val wasAfterDot = afterDot
            afterDot = token.type == GroovyLangLexer.DOT
            when {
                keyword -> {
                    keyword = false
                    declaredNames += token.text
                }
                token.type in DECLARATION_KEYWORDS && !wasAfterDot -> keyword = true
            }
        }

        private fun header(token: Token) {
            when {
                token.type == GroovyLangLexer.EXTENDS -> {
                    inExtends = true
                    extendsName = ""
                }
                inExtends && token.type in NAME_TOKENS -> extendsName = (extendsName ?: "") + token.text
                else -> inExtends = false
            }
        }
    }

    // Not private so tests can exercise the prelude scan directly; the class itself is internal.
    companion object {
        private val DECLARATION_KEYWORDS = setOf(
            GroovyLangLexer.CLASS,
            GroovyLangLexer.INTERFACE,
            GroovyLangLexer.TRAIT,
            GroovyLangLexer.ENUM,
            GroovyLangLexer.RECORD,
        )
        private val NAME_TOKENS = setOf(
            GroovyLangLexer.Identifier,
            GroovyLangLexer.CapitalizedIdentifier,
            GroovyLangLexer.DOT,
        )
        private val OBJECT_SPELLINGS = setOf("java.lang.Object", "Object")
        private const val MAX_SOURCE_CHARS = 1_000_000

        private fun isDegraded(outcome: ParseOutcome): Boolean =
            outcome.fallback != null || outcome.excludedSources.isNotEmpty() || outcome.stubbedClasses.isNotEmpty()

        private fun importsOf(module: ModuleNode): String = (
            module.imports.map { "${it.className} as ${it.alias}" } + module.starImports.map { "${it.packageName}*" }
            ).joinToString(";")

        /** Superclass and interface spellings as written, for [node] and each enclosing class, plus [imports]. */
        private fun declaration(node: ClassNode, imports: String): String = generateSequence(node) { it.outerClass }
            .joinToString("|") { type ->
                val superclass = type.getUnresolvedSuperClass(false)?.unresolvedName?.let(::spelling)
                val interfaces = type.getUnresolvedInterfaces(false).orEmpty()
                    .joinToString(",") { spelling(it.unresolvedName) }
                "${type.name}:$superclass:$interfaces"
            } + "|" + imports

        /**
         * A written type name with nested-class separators unified. Resolving `Outer.Inner` as a static inner
         * class can leave `Outer$Inner` as the unresolved name, while the same spelling that fails to resolve
         * stays `Outer.Inner`; both name the same class, so a clean and a degraded compile must agree.
         */
        private fun spelling(name: String): String = name.replace('$', '.')

        /**
         * The identity of the class named [fqn] in [text], hashed (see [PreludeScanner.identity]), with its
         * written `extends` name; null when the declaration is not found or the text does not lex. Comments and
         * string literals are not significant, so they can neither end the prelude nor change the hash. The whole
         * file is lexed, so every declared class name is included (see [scanPrelude]); a file that does not lex,
         * for example with an unterminated string, has no prelude, which denies reuse.
         */
        fun preludeOf(text: String, fqn: String): Prelude? {
            if (text.length > MAX_SOURCE_CHARS) return null
            val path = fqn.substringAfterLast('.').split('$')
            return runCatching { scanPrelude(GroovyLangLexer(CharStreams.fromString(text)), path) }
                .onFailure { failure -> if (failure is Error && failure !is GroovySyntaxError) throw failure }
                .getOrNull()
        }

        /**
         * Lexes the whole file: the target's identity up to its body, then only the declared class names, so
         * a name-shadowing class anywhere in the file counts but bodies never do.
         *
         * A lex error before the end of the target's header gives no prelude, which denies reuse. A lex error
         * after it (a half-typed string in a body, say) keeps the names gathered so far: the text past that
         * point is not valid Groovy, so no compile can bind a name to a class declared there until the file
         * lexes again, and that compile records a fresh verdict. Local classes in bodies and `[class: …]`
         * map keys are collected as declared names too, which can only make an edit deny reuse.
         */
        private fun scanPrelude(lexer: GroovyLangLexer, path: List<String>): Prelude? {
            val scanner = PreludeScanner(path)
            var identity: String? = null
            var extendsName: String? = null
            var token = nextOrNull(lexer)
            while (token != null && token.type != Token.EOF) {
                if (token.channel == Token.DEFAULT_CHANNEL && token.type != GroovyLangLexer.NL) {
                    when {
                        identity != null -> scanner.collectName(token)
                        scanner.accept(token) -> {
                            identity = scanner.identity
                            extendsName = scanner.extendsName
                        }
                    }
                }
                token = nextOrNull(lexer)
            }
            val names = scanner.declaredNames.sorted().joinToString(",")
            return identity?.let { Prelude(sha256("$it | classes: $names"), extendsName) }
        }

        /** The next token, or null when the text stops lexing; real errors other than syntax errors propagate. */
        private fun nextOrNull(lexer: GroovyLangLexer): Token? = runCatching { lexer.nextToken() }
            .onFailure { failure -> if (failure is Error && failure !is GroovySyntaxError) throw failure }
            .getOrNull()

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Source unit names and excluded-source locators as normalized paths (`file:` URLs or plain paths). */
        private fun sourceKey(name: String): String = runCatching {
            val path = if (name.startsWith("file:")) Path.of(URI(name)) else Path.of(name)
            path.toAbsolutePath().normalize().toString()
        }.getOrDefault(name)
    }
}
