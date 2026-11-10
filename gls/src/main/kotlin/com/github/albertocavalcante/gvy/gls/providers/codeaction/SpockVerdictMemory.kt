package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseOutcome
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The last confident SPECIFICATION verdicts per open document, read only by the table action (#1387).
 *
 * A spec proven by resolution loses its verdict while a base it extends fails to compile: gls excludes
 * the failing file, the base becomes a stub, and the class is UNCERTAIN. A clean compile records each
 * confident spec with its declaration (class headers and imports) and the bases it was proven through.
 * A later degraded compile (excluded sources, stubbed classes or a CONVERSION fallback) recalls a spec only
 * when the compile fingerprint is unchanged, the declaration is unchanged, and the degraded part is that
 * same base: one of its source files is excluded or one of its classes is stubbed, or, for a base from a
 * jar, the whole compile fell back. The next clean compile replaces the record; closing the document
 * drops it. Only names and strings are kept, never AST nodes.
 *
 * Known limit: while the base cannot be read, the recalled verdict is a guess about the broken file. If
 * an edit both stops the base extending `Specification` and breaks it, the action stays offered until the
 * base compiles again. That is acceptable for a whitespace-only, reversible alignment; nothing else reads
 * this memory.
 */
internal class SpockVerdictMemory {
    private data class Remembered(
        val declaration: String,
        val baseSources: Set<String>,
        val baseNames: Set<String>,
        val jvmBase: Boolean,
    )

    private class Record(val fingerprint: String, val classes: Map<String, Remembered>)

    private val records = ConcurrentHashMap<String, Record>()

    /** One request's view of the memory: the document's compile fingerprint and parse outcome. */
    class Access(
        private val memory: SpockVerdictMemory,
        private val fingerprint: String,
        private val outcome: ParseOutcome,
    ) {
        /**
         * A clean compile replaces the record for [uri] and recalls nothing. A degraded compile returns the
         * names of UNCERTAIN classes whose remembered SPECIFICATION still applies (see the class docs).
         */
        fun exchange(
            uri: String,
            module: ModuleNode,
            source: SpockSourceContext.Available,
            content: String,
        ): Set<String> {
            val declarations = Declarations(module, content)
            if (!isDegraded(outcome)) {
                memory.records[uri] = Record(fingerprint, remembered(module, source, declarations))
                return emptySet()
            }
            val record = memory.records[uri]?.takeIf { it.fingerprint == fingerprint } ?: return emptySet()
            return source.classes.filter { it.status == SpecificationStatus.UNCERTAIN }
                .mapNotNullTo(HashSet()) { context ->
                    val remembered = record.classes[context.classNode.name]
                    context.classNode.name.takeIf {
                        remembered != null && sameBaseDegraded(remembered) &&
                            remembered.declaration == declarations.of(context.classNode)
                    }
                }
        }

        private fun remembered(
            module: ModuleNode,
            source: SpockSourceContext.Available,
            declarations: Declarations,
        ): Map<String, Remembered> = source.classes.filter { it.status == SpecificationStatus.SPECIFICATION }
            .associate { context ->
                val bases = Bases.of(context.classNode, module)
                context.classNode.name to
                    Remembered(declarations.of(context.classNode), bases.sources, bases.names, bases.jvm)
            }

        private fun sameBaseDegraded(remembered: Remembered): Boolean {
            val excluded = outcome.excludedSources.mapTo(HashSet()) { sourceKey(it.source) }
            return remembered.baseSources.any { it in excluded } ||
                remembered.baseNames.any { it in outcome.stubbedClasses } ||
                (remembered.jvmBase && outcome.fallback != null)
        }
    }

    fun forget(uri: String) {
        records.remove(uri)
    }

    /** Header text of a class and its enclosing classes, plus the module's imports: the superclass binding inputs. */
    private class Declarations(module: ModuleNode, private val content: String) {
        private val lineStarts = buildList {
            add(0)
            content.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        private val imports = (
            module.imports.map { "${it.className} as ${it.alias}" } +
                module.starImports.map { "${it.packageName}*" }
            ).joinToString(";")

        fun of(node: ClassNode): String = generateSequence(node) { it.outerClass }
            .joinToString("|") { header(it) } + "|" + imports

        private fun header(node: ClassNode): String {
            val start = lineStarts.getOrNull(node.lineNumber - 1)?.plus(node.columnNumber - 1) ?: return node.name
            val end = content.indexOf('{', start).takeIf { it >= 0 } ?: return node.name
            return content.substring(start, end).split(WHITESPACE).filter(String::isNotEmpty).joinToString(" ")
        }
    }

    /** The superclass chain above a class: other files' sources and names, and whether it reaches a JVM class. */
    private class Bases(val sources: Set<String>, val names: Set<String>, val jvm: Boolean) {
        companion object {
            fun of(node: ClassNode, module: ModuleNode): Bases {
                val sources = mutableSetOf<String>()
                val names = mutableSetOf<String>()
                val seen: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())
                var link = node.superClass?.redirect()
                var jvm = false
                // Plain properties and a visited set only: Groovy's hierarchy helpers loop on a cycle (#1369).
                while (link != null && !jvm && seen.add(link)) {
                    when {
                        link.name == ClassHelper.OBJECT_TYPE.name -> link = null
                        link.isResolved -> jvm = true
                        else -> {
                            names += link.name
                            link.module?.takeIf { it !== module }?.context?.name?.let { sources += sourceKey(it) }
                            link = link.superClass?.redirect()
                        }
                    }
                }
                return Bases(sources, names, jvm)
            }
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        fun isDegraded(outcome: ParseOutcome): Boolean =
            outcome.fallback != null || outcome.excludedSources.isNotEmpty() || outcome.stubbedClasses.isNotEmpty()

        /** Source unit names and excluded-source locators as normalized paths (`file:` URLs or plain paths). */
        fun sourceKey(name: String): String = runCatching {
            val path = if (name.startsWith("file:")) Path.of(URI(name)) else Path.of(name)
            path.toAbsolutePath().normalize().toString()
        }.getOrDefault(name)
    }
}
