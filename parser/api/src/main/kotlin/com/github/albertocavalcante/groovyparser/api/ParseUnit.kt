package com.github.albertocavalcante.groovyparser.api

import com.github.albertocavalcante.groovyparser.api.model.Diagnostic
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.SymbolInfo
import com.github.albertocavalcante.groovyparser.api.model.TypeInfo
import java.nio.file.Path

/**
 * Abstraction over a parsed Groovy source file.
 *
 * This interface provides a parser-agnostic way to query parsed code.
 * Different parser implementations (native, core, rewrite) provide their
 * own implementations of this interface.
 *
 * Example usage:
 * ```kotlin
 * val provider = ParserRegistry.default()
 * val parseUnit = provider.parse("class Foo {}")
 *
 * // Query diagnostics
 * val errors = parseUnit.diagnostics().filter { it.severity == Severity.ERROR }
 *
 * // Query node at position
 * val node = parseUnit.nodeAt(Position(line = 1, column = 10))
 * ```
 */
interface ParseUnit {
    /**
     * The original source code that was parsed.
     */
    val source: String

    /**
     * The file path, if this was parsed from a file.
     */
    val path: Path?

    /**
     * Whether parsing completed successfully (no fatal errors).
     */
    val isSuccessful: Boolean

    /**
     * Returns the AST node at the given position, if any.
     *
     * @param position The source position to query
     * @return Node information at that position, or null if no node found
     */
    fun nodeAt(position: Position): NodeInfo?

    /**
     * Returns all diagnostics (errors, warnings) from parsing.
     */
    fun diagnostics(): List<Diagnostic>

    /**
     * Returns all symbols (classes, methods, fields, variables) in this unit.
     */
    fun symbols(): List<SymbolInfo>

    /**
     * Returns type information at the given position.
     *
     * @param position The source position to query
     * @return Type information, or null if type cannot be determined
     */
    fun typeAt(position: Position): TypeInfo?
}
