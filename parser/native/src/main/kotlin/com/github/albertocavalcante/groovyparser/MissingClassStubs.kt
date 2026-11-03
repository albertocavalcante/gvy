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

    private class Stub(val fqn: String, var kind: Kind = Kind.CLASS, var typeParameters: Int = 0) {
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
     * Learns from the errors of [unit] (any source of it): stubs each class reported unresolved, and
     * corrects the kind of those already stubbed. Returns whether the stubs changed.
     */
    fun learnFrom(unit: CompilationUnit): Boolean {
        val causes = unit.errorCollector.errors.orEmpty().mapNotNull { (it as? SyntaxErrorMessage)?.cause }
        if (causes.isEmpty()) return false
        val existing = declaredClasses(unit)
        val packages = packagesBySource(unit)
        var changed = false
        for (cause in causes) {
            changed = (stubUnresolved(cause, existing, packages) || correctKind(cause)) || changed
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

    private fun stubUnresolved(cause: SyntaxException, existing: Set<String>, packages: Map<String, String>): Boolean {
        val match = UNRESOLVED.find(cause.originalMessage.orEmpty()) ?: return false
        val name = match.groupValues[1]
        if (!isStubbable(name)) return false
        val fqn = if ('.' in name) name else packages[cause.sourceLocator].orEmpty() + name
        if (fqn in existing || fqn in stubs) return false
        val kind = if (match.groupValues[2].isNotEmpty()) Kind.ANNOTATION else Kind.CLASS
        stubs[fqn] = Stub(fqn, kind)
        return true
    }

    private fun correctKind(cause: SyntaxException): Boolean {
        val message = cause.originalMessage.orEmpty()
        val interfaceOf = MUST_BE_INTERFACE.find(message)
        val notAnnotation = NOT_AN_ANNOTATION.find(message)
        val noParameters = TAKES_NO_PARAMETERS.find(message)
        return when {
            interfaceOf != null -> setKind(interfaceOf.groupValues[1], Kind.INTERFACE)
            notAnnotation != null -> setKind(notAnnotation.groupValues[1], Kind.ANNOTATION)
            noParameters != null -> setTypeParameters(noParameters.groupValues[2], noParameters.groupValues[1].toInt())
            else -> false
        }
    }

    private fun setKind(fqn: String, kind: Kind): Boolean {
        val stub = stubs[fqn]?.takeIf { it.kind != kind } ?: return false
        stub.kind = kind
        return true
    }

    private fun setTypeParameters(fqn: String, count: Int): Boolean {
        val stub = stubs[fqn]?.takeIf { it.typeParameters != count } ?: return false
        stub.typeParameters = count
        return true
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
                if (stubs.learnFrom(result.compilationUnit)) result = compile(request, stubs)
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
