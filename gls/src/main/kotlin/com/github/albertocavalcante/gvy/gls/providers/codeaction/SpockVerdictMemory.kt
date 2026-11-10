package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseOutcome
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
 * - every in-workspace base's prelude (its file from the start to the class body, covering imports,
 *   annotations and the `extends` clause) still hashes to what the clean compile saw. That text check can
 *   only deny reuse: it catches a base edited to stop extending `Specification` and then broken, whether or
 *   not the table action ran in between.
 *
 * The record is replaced by the next clean compile the table action sees, recomputed only when the compiled
 * AST changes, and dropped when the document closes; a write from a request that started before the close
 * is discarded. Only strings and hashes are kept, never AST nodes beyond a weak reference to the last module.
 *
 * Known limit: the recalled verdict assumes the broken base still extends what its prelude says. An edit
 * that changes spec identity outside the prelude, for example through a base's own base in a jar, is not
 * seen until the base compiles again. That is acceptable for a whitespace-only, reversible alignment;
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

        private fun preludesUnchanged(remembered: Remembered): Boolean = remembered.bases.all { base ->
            base.source == null || base.prelude == readSource(base.source)?.let { prelude(it, base.name) }
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
                        val prelude = sourceKey?.let(readSource)?.let { prelude(it, link.name) }
                        bases += Base(sourceKey, link.name, prelude)
                        link = link.superClass?.redirect()
                    }
                }
            }
            return Chain(bases, jvm)
        }
    }

    private class Chain(val bases: List<Base>, val jvm: Boolean)

    private companion object {
        val WHITESPACE = Regex("\\s+")
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
         * A hash of [text] from its start to the body of the class named [fqn], with whitespace collapsed;
         * null when the declaration is not found. Text-based on purpose: the base may not parse, and a
         * mismatch only denies reuse.
         */
        fun prelude(text: String, fqn: String): String? {
            if (text.length > MAX_SOURCE_CHARS) return null
            val simple = Regex.escape(fqn.substringAfterLast('.').substringAfterLast('$'))
            val declaration = Regex("\\b(class|interface|trait|enum)\\s+$simple\\b").find(text) ?: return null
            val body = text.indexOf('{', declaration.range.last + 1).takeIf { it >= 0 } ?: return null
            val normalized = text.substring(0, body).split(WHITESPACE).filter(String::isNotEmpty).joinToString(" ")
            return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /** Source unit names and excluded-source locators as normalized paths (`file:` URLs or plain paths). */
        fun sourceKey(name: String): String = runCatching {
            val path = if (name.startsWith("file:")) Path.of(URI(name)) else Path.of(name)
            path.toAbsolutePath().normalize().toString()
        }.getOrDefault(name)
    }
}
