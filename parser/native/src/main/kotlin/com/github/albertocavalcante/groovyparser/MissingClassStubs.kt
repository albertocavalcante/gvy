package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import com.github.albertocavalcante.gvy.common.ast.stubSourceName
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Empty stand-ins for classes Groovy cannot resolve.
 *
 * Groovy stops resolving at the first "unable to resolve class X": the failing file's other types stay
 * unresolved and so do the files after it. Declaring an empty `X` makes Groovy resolve everything else
 * by its own rules (nested classes, type variables, the default package), which beats imitating them.
 *
 * The kind of a stub is a guess (`class`) that Groovy's own errors on the next compile correct: see
 * the patterns below, taken from Groovy 4's messages.
 */
internal class MissingClassStubs {

    private enum class Kind(val keyword: String) { CLASS("class"), INTERFACE("interface"), ANNOTATION("@interface") }

    private data class Stub(val fqn: String, val kind: Kind = Kind.CLASS, val typeParameters: Int = 0) {
        fun source(): String {
            val pkg = fqn.substringBeforeLast('.', "")
            val params = (0 until typeParameters).joinToString(",", "<", ">") { "T$it" }.takeIf { typeParameters > 0 }
            val header = if (pkg.isEmpty()) "" else "package $pkg\n"
            return header + "${kind.keyword} ${fqn.substringAfterLast('.')}${params.orEmpty()} {}"
        }
    }

    private val stubs = linkedMapOf<String, Stub>()

    /** Fully qualified names of the stubbed classes, in the order they were found. */
    val names: List<String> get() = stubs.keys.toList()

    /**
     * Learns from the errors of [unit]: stubs each class reported unresolved, and corrects the kind of
     * those already stubbed. Returns whether the stubs changed. A bare name is only stubbed for an error
     * of [ownSource]: placed in the package, a stub for another file's miss (a forgotten import, say)
     * would beat this file's working star import of the real class, as Groovy tries the package first.
     */
    fun learnFrom(unit: CompilationUnit, ownSource: String): Boolean {
        val causes = unit.errorCollector.errors.orEmpty().mapNotNull { (it as? SyntaxErrorMessage)?.cause }
        if (causes.isEmpty()) return false
        val existing = declaredClasses(unit)
        val packages = packagesBySource(unit)
        var changed = false
        for (cause in causes) {
            changed = (stubUnresolved(cause, ownSource, existing, packages) || correctKind(cause)) || changed
            if (stubs.size >= MAX_STUBS) break
        }
        return changed
    }

    /** Adds one source per stub to [unit]; they are named with the shared stub prefix. */
    fun addTo(unit: CompilationUnit, config: CompilerConfiguration, loader: GroovyClassLoader) {
        stubs.values.forEach { stub ->
            val source = SourceUnit(
                stubSourceName(stub.fqn),
                StringReaderSource(stub.source(), config),
                config,
                loader,
                unit.errorCollector,
            )
            unit.addSource(source)
        }
    }

    private fun stubUnresolved(
        cause: SyntaxException,
        ownSource: String,
        existing: Set<String>,
        packages: Map<String, String>,
    ): Boolean {
        val match = UNRESOLVED.find(cause.originalMessage.orEmpty())?.takeIf { stubs.size < MAX_STUBS } ?: return false
        val name = match.groupValues[1]
        val fqn = when {
            !isStubbable(name) -> null
            '.' in name -> name
            cause.sourceLocator == ownSource -> packages[cause.sourceLocator].orEmpty() + name
            else -> null
        }?.takeUnless { it in existing || it in stubs } ?: return false
        stubs[fqn] = Stub(fqn, if (match.groupValues[2].isNotEmpty()) Kind.ANNOTATION else Kind.CLASS)
        return true
    }

    /** An error [pattern] naming a stub (in group [fqnGroup]) that must be reshaped. */
    private class Correction(val pattern: Regex, val fqnGroup: Int, val reshape: (Stub, MatchResult) -> Stub)

    /** Applies the first of [CORRECTIONS] that matches [cause]; returns whether a stub changed. */
    private fun correctKind(cause: SyntaxException): Boolean {
        val message = cause.originalMessage.orEmpty()
        for (correction in CORRECTIONS) {
            val match = correction.pattern.find(message) ?: continue
            return update(match.groupValues[correction.fqnGroup]) { correction.reshape(it, match) }
        }
        return false
    }

    /** Replaces the stub for [fqn] with [change] of it; returns whether that changed anything. */
    private fun update(fqn: String, change: (Stub) -> Stub): Boolean {
        val stub = stubs[fqn] ?: return false
        val changed = change(stub)
        stubs[fqn] = changed
        return changed != stub
    }

    private fun declaredClasses(unit: CompilationUnit): Set<String> =
        unit.ast.modules.flatMapTo(HashSet()) { module -> module.classes.map { it.name } }

    /** The package prefix (`a.b.`, or empty) of each source, by source unit name. */
    private fun packagesBySource(unit: CompilationUnit): Map<String, String> = unit.ast.modules
        .mapNotNull { module -> module.context?.name?.let { it to module.packageName.orEmpty() } }
        .toMap()

    companion object {
        /**
         * Compiles [request] with [compile]; when classes cannot be resolved, compiles again with an empty
         * stub for each so Groovy resolves everything else. The original "unable to resolve" errors of the
         * requested file stay in the diagnostics, and the stubs are listed in the outcome.
         */
        fun compile(request: ParseRequest, compile: (ParseRequest, MissingClassStubs?) -> ParseResult): ParseResult {
            val first = compile(request, null)
            if (request.compilePhase <= Phases.CONVERSION) return first

            val stubs = MissingClassStubs()
            var result = first
            repeat(MAX_ROUNDS) {
                if (stubs.learnFrom(result.compilationUnit, request.sourceUnitName)) result = compile(request, stubs)
            }
            if (stubs.names.isEmpty()) return first

            val ownErrors = setOf(request.sourceUnitName)
            val unresolved = ParserDiagnosticConverter.convert(first.compilationUnit.errorCollector, ownErrors)
                .filter { UNRESOLVED.containsMatchIn(it.message) }
            return result.copy(
                diagnostics = (unresolved + result.diagnostics).distinct(),
                outcome = result.outcome.copy(stubbedClasses = stubs.names),
            )
        }

        private const val MAX_ROUNDS = 3
        private const val MAX_STUBS = 64

        /**
         * `unable to resolve class X`, plus ` for annotation` when X is used as an annotation. Groovy
         * prints the type arguments of a generic use (`X<java.lang.String>`) after the name: not captured.
         */
        internal val UNRESOLVED = Regex("""unable to resolve class ([^\s<\[]+)\S*( for annotation)?""")

        /** `You are not allowed to implement the class 'X', use extends instead.` (also for interfaces). */
        internal val MUST_BE_INTERFACE = Regex("""not allowed to implement the class '([^']+)'""")

        /** `class X is not an annotation in @X`. */
        internal val NOT_AN_ANNOTATION = Regex("""class (\S+) is not an annotation""")

        /** `The class X<String> (supplied with N type parameters) refers to the class X which takes no parameters`. */
        internal val TAKES_NO_PARAMETERS =
            Regex("""supplied with (\d+) type parameters?\) refers to the class (\S+) which takes no parameters""")

        /** Groovy errors that say a stub has the wrong shape, in the order they are tried. */
        private val CORRECTIONS = listOf(
            Correction(MUST_BE_INTERFACE, fqnGroup = 1) { stub, _ -> stub.copy(kind = Kind.INTERFACE) },
            Correction(NOT_AN_ANNOTATION, fqnGroup = 1) { stub, _ -> stub.copy(kind = Kind.ANNOTATION) },
            Correction(TAKES_NO_PARAMETERS, fqnGroup = 2) { stub, match ->
                stub.copy(typeParameters = match.groupValues[1].toInt())
            },
        )

        private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_]*""")

        /**
         * A name worth a stub: identifiers joined by dots, not nested (`Outer.Inner`, `Outer$Inner`), since
         * a stub cannot stand for a member of a class and that error is left as it is.
         */
        fun isStubbable(name: String): Boolean {
            val segments = name.split('.')
            return segments.all { IDENTIFIER.matches(it) } &&
                segments.dropLast(1).none { it.first().isUpperCase() }
        }
    }
}
