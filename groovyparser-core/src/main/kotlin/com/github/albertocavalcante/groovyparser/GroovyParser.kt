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
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
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
 */
class GroovyParser(val configuration: ParserConfiguration = ParserConfiguration()) {
    private val logger = LoggerFactory.getLogger(GroovyParser::class.java)
    private val converter = GroovyAstConverter()

    /**
     * Parses Groovy source code and returns a [ParseResult] containing
     * the [CompilationUnit] or any problems encountered.
     *
     * @param code the Groovy source code to parse
     * @return a [ParseResult] containing the parsed AST or problems
     */
    fun parse(code: String): ParseResult<CompilationUnit> {
        val problems = mutableListOf<Problem>()

        try {
            val config = createCompilerConfiguration()
            val classLoader = GroovyClassLoader(Thread.currentThread().contextClassLoader)
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

            try {
                compilationUnit.compile(Phases.CONVERSION)
            } catch (e: CompilationFailedException) {
                logger.debug("Compilation failed: ${e.message}")
                // Continue to extract whatever AST we can get
            }

            // Collect problems from error collector
            collectProblems(compilationUnit.errorCollector, problems)

            // Extract AST
            val ast = compilationUnit.ast
            val moduleNode = ast?.modules?.firstOrNull()

            return if (moduleNode != null) {
                val unit = converter.convert(moduleNode)
                ParseResult(unit, problems)
            } else {
                if (problems.isEmpty()) {
                    // Empty source or script with no classes
                    ParseResult(CompilationUnit(), problems)
                } else {
                    ParseResult(null, problems)
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error during parsing", e)
            problems.add(Problem("Internal error: ${e.message}", cause = e))
            return ParseResult(null, problems)
        }
    }

    private fun createCompilerConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        targetDirectory = null
        sourceEncoding = configuration.characterEncoding.name()
    }

    private fun collectProblems(errorCollector: ErrorCollector?, problems: MutableList<Problem>) {
        errorCollector?.errors?.forEach { error ->
            when (error) {
                is SyntaxErrorMessage -> {
                    val cause = error.cause
                    if (cause is SyntaxException) {
                        val position = if (cause.line > 0 && cause.startColumn > 0) {
                            Position(cause.line, cause.startColumn)
                        } else {
                            null
                        }
                        problems.add(Problem(cause.message ?: "Syntax error", position))
                    } else {
                        problems.add(Problem(cause?.message ?: "Syntax error"))
                    }
                }
                else -> {
                    problems.add(Problem(error.toString()))
                }
            }
        }
    }
}
