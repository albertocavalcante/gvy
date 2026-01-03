package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.internal.GroovyAstConverter
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.control.messages.WarningMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.slf4j.LoggerFactory
import org.codehaus.groovy.control.CompilationUnit as GroovyCompilationUnit

/**
 * Parses Groovy source code and creates Abstract Syntax Trees.
 *
 * This is the main entry point for parsing Groovy code. You can either use
 * this class directly with configuration, or use [StaticGroovyParser] for
 * convenience methods.
 *
 * Example usage:
 * ```kotlin
 * val parser = GroovyParser()
 * val result = parser.parse("class Foo {}")
 * if (result.isSuccessful) {
 *     val unit = result.result.get()
 *     println(unit.types[0].name) // "Foo"
 * }
 * ```
 *
 * Error Recovery:
 * By default, the parser operates in lenient mode, attempting to recover from
 * errors and return partial ASTs when possible. All errors are collected as
 * [Problem]s with appropriate severity levels.
 */
// TODO(#586): Address detekt lint debt in parser/core.
//   See: https://github.com/albertocavalcante/gvy/issues/586
class GroovyParser(val configuration: ParserConfiguration = ParserConfiguration()) {
    private val logger = LoggerFactory.getLogger(GroovyParser::class.java)

    companion object {
        /** Default tolerance level for lenient parsing mode */
        private const val DEFAULT_LENIENT_TOLERANCE = 10

        /**
         * Converts a native Groovy ModuleNode to our custom CompilationUnit.
         *
         * This is useful when you already have a ModuleNode from another parser
         * (like GroovyParserFacade) and want to use the JavaParser-like AST.
         *
         * Each call creates a new converter instance to ensure thread-safety.
         *
         * @param moduleNode the native Groovy ModuleNode
         * @param source optional source code for comment extraction
         * @return the converted CompilationUnit
         */
        fun convertFromNative(
            moduleNode: org.codehaus.groovy.ast.ModuleNode,
            source: String? = null,
        ): CompilationUnit = GroovyAstConverter().convert(moduleNode, source)
    }

    /**
     * Parses Groovy source code and returns a [ParseResult] containing
     * the [CompilationUnit] or any problems encountered.
     *
     * In lenient mode (default), the parser will attempt to recover from
     * errors and return a partial AST with problems. In strict mode,
     * a null result is returned on any error.
     *
     * @param code the Groovy source code to parse
     * @return a [ParseResult] containing the parsed AST or problems
     */
    fun parse(code: String): ParseResult<CompilationUnit> {
        val problems = mutableListOf<Problem>()

        try {
            val config = createCompilerConfiguration()

            return GroovyClassLoader(Thread.currentThread().contextClassLoader).use { classLoader ->
                val compilationUnit = GroovyCompilationUnit(config, null, classLoader)
                val sourceUnit = SourceUnit(
                    "Script.groovy",
                    StringReaderSource(code, config),
                    config,
                    classLoader,
                    compilationUnit.errorCollector,
                )
                compilationUnit.addSource(sourceUnit)

                val hadCompilationError = runCompilation(compilationUnit)
                if (hadCompilationError && !configuration.lenientMode) {
                    collectProblems(compilationUnit.errorCollector, problems)
                    return@use ParseResult(null, problems)
                }

                collectProblems(compilationUnit.errorCollector, problems)
                extractCompilationUnit(compilationUnit, code, problems)
            }
        } catch (e: Exception) {
            return handleInternalError(e, problems)
        }
    }

    private fun runCompilation(compilationUnit: GroovyCompilationUnit): Boolean = try {
        compilationUnit.compile(Phases.CONVERSION)
        false
    } catch (e: CompilationFailedException) {
        logger.debug("Compilation failed: ${e.message}")
        true
    }

    private fun extractCompilationUnit(
        compilationUnit: GroovyCompilationUnit,
        code: String,
        problems: MutableList<Problem>,
    ): ParseResult<CompilationUnit> {
        val moduleNode = compilationUnit.ast?.modules?.firstOrNull()

        return when {
            moduleNode != null -> convertModuleNode(moduleNode, code, problems)
            problems.isEmpty() || configuration.lenientMode -> ParseResult(CompilationUnit(), problems)
            else -> ParseResult(null, problems)
        }
    }

    private fun convertModuleNode(
        moduleNode: org.codehaus.groovy.ast.ModuleNode,
        code: String,
        problems: MutableList<Problem>,
    ): ParseResult<CompilationUnit> = try {
        val sourceForComments = if (configuration.attributeComments) code else null
        val unit = GroovyAstConverter().convert(moduleNode, sourceForComments)
        ParseResult(unit, problems)
    } catch (e: Exception) {
        logger.warn("AST conversion error: ${e.message}", e)
        problems.add(Problem.error("AST conversion error: ${e.message}"))
        if (configuration.lenientMode) ParseResult(CompilationUnit(), problems) else ParseResult(null, problems)
    }

    private fun handleInternalError(e: Exception, problems: MutableList<Problem>): ParseResult<CompilationUnit> {
        logger.error("Unexpected error during parsing", e)
        problems.add(
            Problem(
                message = "Internal error: ${e.message}",
                position = null,
                range = null,
                severity = ProblemSeverity.ERROR,
                cause = e,
            ),
        )
        return ParseResult(null, problems)
    }

    private fun createCompilerConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        targetDirectory = null
        sourceEncoding = configuration.characterEncoding.name()
        // Configure tolerance level for parsing
        tolerance = if (configuration.lenientMode) DEFAULT_LENIENT_TOLERANCE else 0
    }

    private fun collectProblems(errorCollector: ErrorCollector?, problems: MutableList<Problem>) {
        if (errorCollector == null) return

        errorCollector.errors?.forEach { error ->
            when (error) {
                is SyntaxErrorMessage -> handleSyntaxError(error.cause, problems)
                is ExceptionMessage -> handleExceptionMessage(error.cause, problems)
                is SimpleMessage -> problems.add(Problem.error(error.message ?: "Error"))
                else -> problems.add(Problem.error(error.toString()))
            }
        }

        if (configuration.collectWarnings) {
            errorCollector.warnings?.forEach { warning ->
                if (warning is WarningMessage) {
                    problems.add(Problem.warning(warning.message ?: "Warning"))
                }
            }
        }
    }

    private fun handleSyntaxError(cause: Exception?, problems: MutableList<Problem>) {
        if (cause !is SyntaxException) {
            problems.add(Problem.error(cause?.message ?: "Syntax error"))
            return
        }

        val range = createRange(cause)
        val position = range?.begin ?: if (cause.line > 0 && cause.startColumn > 0) {
            Position(cause.line, cause.startColumn)
        } else {
            null
        }

        problems.add(
            Problem(
                message = cause.message ?: "Syntax error",
                position = position,
                range = range,
                severity = ProblemSeverity.ERROR,
                cause = cause,
            ),
        )
    }

    private fun handleExceptionMessage(cause: Throwable?, problems: MutableList<Problem>) {
        problems.add(
            Problem(
                message = cause?.message ?: "Compilation error",
                position = null,
                range = null,
                severity = ProblemSeverity.ERROR,
                cause = cause,
            ),
        )
    }

    /**
     * Creates a Range from a SyntaxException if possible.
     */
    private fun createRange(e: SyntaxException): Range? {
        val startLine = e.line
        val startCol = e.startColumn
        val endLine = e.endLine
        val endCol = e.endColumn

        return if (startLine > 0 && startCol > 0) {
            val begin = Position(startLine, startCol)
            val end = if (endLine > 0 && endCol > 0) {
                Position(endLine, endCol)
            } else {
                // Fallback: end at same position
                begin
            }
            Range(begin, end)
        } else {
            null
        }
    }
}
