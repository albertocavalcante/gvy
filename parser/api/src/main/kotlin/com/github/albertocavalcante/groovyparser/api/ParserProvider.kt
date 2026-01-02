package com.github.albertocavalcante.groovyparser.api

import java.nio.file.Path

/**
 * Service Provider Interface for Groovy parsers.
 *
 * Implementations of this interface provide parsing capabilities with
 * different trade-offs (speed, features, error recovery, etc.).
 *
 * Example usage:
 * ```kotlin
 * val provider: ParserProvider = NativeParserProvider()
 * val unit = provider.parse("class Foo {}")
 * println(unit.diagnostics())
 * ```
 *
 * @see ParseUnit
 * @see ParserCapabilities
 */
interface ParserProvider {
    /**
     * Human-readable name of this parser implementation.
     */
    val name: String

    /**
     * Capabilities supported by this parser.
     */
    val capabilities: ParserCapabilities

    /**
     * Parses the given Groovy source code.
     *
     * @param source The Groovy source code to parse
     * @param path Optional file path for diagnostics and error messages
     * @return A ParseUnit representing the parsed code
     */
    fun parse(source: String, path: Path? = null): ParseUnit

    /**
     * Parses a file from the filesystem.
     *
     * @param path The path to the Groovy file
     * @return A ParseUnit representing the parsed file
     */
    fun parseFile(path: Path): ParseUnit {
        val source = path.toFile().readText()
        return parse(source, path)
    }
}
