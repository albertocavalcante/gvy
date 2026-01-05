package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.groovyparser.ast.types.Range
import com.github.albertocavalcante.groovyparser.errors.GroovyParserError
import com.github.albertocavalcante.groovyparser.errors.GroovyParserResult
import com.github.albertocavalcante.groovyparser.errors.flatMapResult
import com.github.albertocavalcante.groovyparser.errors.toGroovyParserResult
import org.codehaus.groovy.ast.ASTNode
import java.net.URI

/**
 * Type-safe wrappers for position coordinates
 */
@JvmInline
value class LineNumber(val value: Int) {
    init {
        require(value >= 0) { "Line number must be non-negative, got $value" }
    }

    fun toLspLine(): Int = value
    fun toAstLine(): Int = value + 1

    companion object {
        fun fromLsp(lspLine: Int): LineNumber = LineNumber(lspLine)
        fun fromAst(astLine: Int): LineNumber = LineNumber(maxOf(0, astLine - 1))
    }
}

@JvmInline
value class ColumnNumber(val value: Int) {
    init {
        require(value >= 0) { "Column number must be non-negative, got $value" }
    }

    fun toLspColumn(): Int = value
    fun toAstColumn(): Int = value + 1

    companion object {
        fun fromLsp(lspColumn: Int): ColumnNumber = ColumnNumber(lspColumn)
        fun fromAst(astColumn: Int): ColumnNumber = ColumnNumber(maxOf(0, astColumn - 1))
    }
}

/**
 * Type-safe position with validation
 */
data class SafePosition(val line: LineNumber, val column: ColumnNumber) {
    fun toParserPosition(): Position = Position(line.value, column.value)

    fun isWithin(start: SafePosition, end: SafePosition): Boolean = when {
        line.value < start.line.value -> false
        line.value > end.line.value -> false
        line.value == start.line.value && column.value < start.column.value -> false
        line.value == end.line.value && column.value > end.column.value -> false
        else -> true
    }

    companion object {
        fun fromGroovyPosition(position: Position): SafePosition = SafePosition(
            LineNumber.fromLsp(position.line),
            ColumnNumber.fromLsp(position.character),
        )

        fun fromAst(line: Int, column: Int): GroovyParserResult<SafePosition> = if (line > 0 && column > 0) {
            SafePosition(
                LineNumber.fromAst(line),
                ColumnNumber.fromAst(column),
            ).toGroovyParserResult()
        } else {
            GroovyParserError.InvalidPosition(
                URI.create("unknown"),
                line,
                column,
                "Invalid AST coordinates",
            ).toGroovyParserResult()
        }
    }
}

/**
 * Extension functions for type-safe AST node handling
 */

/**
 * Safely extracts position from an AST node
 */
fun ASTNode.safePosition(): GroovyParserResult<SafePosition> = SafePosition.fromAst(lineNumber, columnNumber)

/**
 * Safely extracts end position from an AST node
 */
fun ASTNode.safeEndPosition(): GroovyParserResult<SafePosition> = SafePosition.fromAst(lastLineNumber, lastColumnNumber)

/**
 * Creates a safe Range from an AST node
 */
fun ASTNode.safeRange(): GroovyParserResult<Range> = safePosition().flatMapResult { start: SafePosition ->
    safeEndPosition().map { end: SafePosition ->
        Range(start.toParserPosition(), end.toParserPosition())
    }
}
