package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * A simpler, static API for parsing Groovy source code.
 *
 * This class provides static methods for common parsing operations, similar to
 * JavaParser's StaticJavaParser. For more control over parsing configuration,
 * use [GroovyParser] directly.
 *
 * Example usage:
 * ```kotlin
 * // Parse a class from string
 * val unit = StaticGroovyParser.parse("class Foo {}")
 *
 * // Parse from file
 * val unitFromFile = StaticGroovyParser.parse(File("MyClass.groovy"))
 *
 * // Parse from path
 * val unitFromPath = StaticGroovyParser.parse(Path.of("src/main/groovy/MyClass.groovy"))
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
     * Parses a Groovy source file.
     *
     * @param file the file to parse
     * @return the parsed [CompilationUnit]
     * @throws ParseProblemException if parsing fails
     * @throws java.io.IOException if the file cannot be read
     */
    fun parse(file: File): CompilationUnit {
        val code = file.readText(getConfiguration().characterEncoding)
        return parse(code)
    }

    /**
     * Parses a Groovy source file from a path.
     *
     * @param path the path to the file to parse
     * @return the parsed [CompilationUnit]
     * @throws ParseProblemException if parsing fails
     * @throws java.io.IOException if the file cannot be read
     */
    fun parse(path: Path): CompilationUnit = parse(path.toFile())

    /**
     * Parses Groovy source code from an input stream.
     *
     * @param inputStream the input stream to read from (will be closed after reading)
     * @return the parsed [CompilationUnit]
     * @throws ParseProblemException if parsing fails
     * @throws java.io.IOException if reading fails
     */
    fun parse(inputStream: InputStream): CompilationUnit {
        val code = inputStream.bufferedReader(getConfiguration().characterEncoding).use { it.readText() }
        return parse(code)
    }

    /**
     * Parses Groovy source code from an input stream with a specific encoding.
     *
     * @param inputStream the input stream to read from (will be closed after reading)
     * @param encoding the character encoding to use
     * @return the parsed [CompilationUnit]
     * @throws ParseProblemException if parsing fails
     * @throws java.io.IOException if reading fails
     */
    fun parse(inputStream: InputStream, encoding: Charset): CompilationUnit {
        val code = inputStream.bufferedReader(encoding).use { it.readText() }
        return parse(code)
    }

    /**
     * Parses Groovy source code from a reader.
     *
     * @param reader the reader to read from (will be closed after reading)
     * @return the parsed [CompilationUnit]
     * @throws ParseProblemException if parsing fails
     * @throws java.io.IOException if reading fails
     */
    fun parse(reader: Reader): CompilationUnit {
        val code = reader.use { it.readText() }
        return parse(code)
    }

    /**
     * Parses Groovy source code without throwing exceptions.
     *
     * @param code the Groovy source code to parse
     * @return a [ParseResult] containing the result or problems
     */
    fun parseResult(code: String): ParseResult<CompilationUnit> {
        val parser = GroovyParser(getConfiguration())
        return parser.parse(code)
    }

    /**
     * Parses a Groovy source file without throwing exceptions.
     *
     * @param file the file to parse
     * @return a [ParseResult] containing the result or problems
     */
    fun parseResult(file: File): ParseResult<CompilationUnit> {
        val code = file.readText(getConfiguration().characterEncoding)
        return parseResult(code)
    }

    /**
     * Parses a Groovy source file from a path without throwing exceptions.
     *
     * @param path the path to the file to parse
     * @return a [ParseResult] containing the result or problems
     */
    fun parseResult(path: Path): ParseResult<CompilationUnit> = parseResult(path.toFile())

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
