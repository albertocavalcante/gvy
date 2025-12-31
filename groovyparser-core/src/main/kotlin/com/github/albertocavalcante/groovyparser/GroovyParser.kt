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
class GroovyParser(val configuration: ParserConfiguration = ParserConfiguration()) {
    private val logger = LoggerFactory.getLogger(GroovyParser::class.java)
    private val converter = GroovyAstConverter()

    companion object {
        /** Default tolerance level for lenient parsing mode */
        private const val DEFAULT_LENIENT_TOLERANCE = 10
        private val sharedConverter = GroovyAstConverter()

        /**
         * Converts a native Groovy ModuleNode to our custom CompilationUnit.
         *
         * This is useful when you already have a ModuleNode from another parser
         * (like GroovyParserFacade) and want to use the JavaParser-like AST.
         *
         * @param moduleNode the native Groovy ModuleNode
         * @param source optional source code for comment extraction
         * @return the converted CompilationUnit
         */
        fun convertFromNative(
            moduleNode: org.codehaus.groovy.ast.ModuleNode,
            source: String? = null,
        ): CompilationUnit = sharedConverter.convert(moduleNode, source)
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

            // Use .use to ensure GroovyClassLoader is properly closed (prevents resource leak)
            return GroovyClassLoader(Thread.currentThread().contextClassLoader).use { classLoader ->
                val compilationUnit = GroovyCompilationUnit(config, null, classLoader)

                val source = StringReaderSource(code, config)
                val sourceUnit = SourceUnit(
                    "Script.groovy",
                    source,
                    config,
                    classLoader,
                    compilationUnit.errorCollector,
                )
                compilationUnit.addSource(sourceUnit)

                var hadCompilationError = false
                try {
                    compilationUnit.compile(Phases.CONVERSION)
                } catch (e: CompilationFailedException) {
                    logger.debug("Compilation failed: ${e.message}")
                    hadCompilationError = true
                    // In lenient mode, continue to extract whatever AST we can get
                    if (!configuration.lenientMode) {
                        collectProblems(compilationUnit.errorCollector, problems)
                        return@use ParseResult(null, problems)
                    }
                }

                // Collect problems from error collector
                collectProblems(compilationUnit.errorCollector, problems)

                // Extract AST
                val ast = compilationUnit.ast
                val moduleNode = ast?.modules?.firstOrNull()

                when {
                    moduleNode != null -> {
                        try {
                            // Pass source for comment extraction if enabled
                            val sourceForComments = if (configuration.attributeComments) code else null
                            val unit = converter.convert(moduleNode, sourceForComments)
                            ParseResult(unit, problems)
                        } catch (e: Exception) {
                            // Conversion error - still return partial info in lenient mode
                            logger.warn("AST conversion error: ${e.message}", e)
                            problems.add(Problem.error("AST conversion error: ${e.message}"))
                            if (configuration.lenientMode) {
                                ParseResult(CompilationUnit(), problems)
                            } else {
                                ParseResult(null, problems)
                            }
                        }
                    }
                    problems.isEmpty() -> {
                        // Empty source or script with no classes
                        ParseResult(CompilationUnit(), problems)
                    }
                    configuration.lenientMode -> {
                        // Return empty compilation unit with problems in lenient mode
                        // (removed && !hadCompilationError to fix lenient mode behavior)
                        ParseResult(CompilationUnit(), problems)
                    }
                    else -> {
                        ParseResult(null, problems)
                    }
                }
            }
        } catch (e: Exception) {
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
    }

    private fun createCompilerConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        targetDirectory = null
        sourceEncoding = configuration.characterEncoding.name()
        // Configure tolerance level for parsing
        tolerance = if (configuration.lenientMode) DEFAULT_LENIENT_TOLERANCE else 0
    }

    private fun collectProblems(errorCollector: ErrorCollector?, problems: MutableList<Problem>) {
        if (errorCollector == null) return

        // Collect errors
        errorCollector.errors?.forEach { error ->
            when (error) {
                is SyntaxErrorMessage -> {
                    val cause = error.cause
                    if (cause is SyntaxException) {
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
                    } else {
                        problems.add(Problem.error(cause?.message ?: "Syntax error"))
                    }
                }
                is ExceptionMessage -> {
                    val cause = error.cause
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
                is SimpleMessage -> {
                    problems.add(Problem.error(error.message ?: "Error"))
                }
                else -> {
                    problems.add(Problem.error(error.toString()))
                }
            }
        }

        // Collect warnings if configured
        if (configuration.collectWarnings) {
            errorCollector.warnings?.forEach { warning ->
                if (warning is WarningMessage) {
                    problems.add(Problem.warning(warning.message ?: "Warning"))
                }
            }
        }
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
