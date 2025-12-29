package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit

/**
 * A simpler, static API for parsing Groovy source code.
 *
 * This class provides static methods for common parsing operations, similar to
 * JavaParser's StaticJavaParser. For more control over parsing configuration,
 * use [GroovyParser] directly.
 *
 * Example usage:
 * ```kotlin
 * // Parse a class
 * val unit = StaticGroovyParser.parse("class Foo {}")
 *
 * // Configure parsing
 * StaticGroovyParser.setConfiguration(
 *     ParserConfiguration().setLanguageLevel(GroovyLanguageLevel.GROOVY_2_4)
 * )
 * ```
 *
 * Note: The configuration is stored in a ThreadLocal, so it's safe to use
 * in multi-threaded environments. Each thread can have its own configuration.
 */
object StaticGroovyParser {

    private val localConfiguration = ThreadLocal.withInitial { ParserConfiguration() }

    /**
     * Gets the current parser configuration.
     */
    fun getConfiguration(): ParserConfiguration = localConfiguration.get()

    /**
     * Sets the parser configuration.
     *
     * Note: This is a thread-local setting, so changes only affect the current thread.
     */
    fun setConfiguration(configuration: ParserConfiguration) {
        localConfiguration.set(configuration)
    }

    /**
     * Parses Groovy source code and returns the resulting [CompilationUnit].
     *
     * @param code the Groovy source code to parse
     * @return the parsed [CompilationUnit]
     * @throws ParseProblemException if parsing fails due to syntax errors
     */
    fun parse(code: String): CompilationUnit {
        val parser = GroovyParser(getConfiguration())
        val result = parser.parse(code)
        return handleResult(result)
    }

    /**
     * Handles the parse result, throwing an exception if parsing failed.
     */
    private fun handleResult(result: ParseResult<CompilationUnit>): CompilationUnit {
        if (result.isSuccessful) {
            return result.result.get()
        }
        throw ParseProblemException(result.problems)
    }
}
