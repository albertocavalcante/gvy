package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.groovyparser.ast.types.Range
import org.codehaus.groovy.ast.ASTNode

/**
 * Utility functions for position conversions between LSP (0-based) and AST (1-based) coordinate systems.
 *
 * LSP Protocol uses 0-based line/character positions
 * Groovy AST uses 1-based line/column positions
 */
object PositionUtils {

    private const val LINE_WEIGHT_MULTIPLIER = 1000
    private const val COLUMN_WEIGHT_MULTIPLIER = 100

    /**
     * Convert LSP position (0-based) to AST position (1-based)
     */
    fun lspToAst(position: Position): Pair<Int, Int> = (position.line + 1) to (position.character + 1)

    /**
     * Convert AST position (1-based) to LSP position (0-based)
     */
    fun astToLsp(line: Int, column: Int): Position = Position(maxOf(0, line - 1), maxOf(0, column - 1))

    /**
     * Convert AST node to LSP Range
     */
    fun astNodeToRange(node: ASTNode): Range? {
        if (!node.hasValidPosition()) return null

        val start = astToLsp(node.lineNumber, node.columnNumber)
        val end = astToLsp(node.lastLineNumber, node.lastColumnNumber)

        return Range(start, end)
    }

    /**
     * Check if AST node has valid position information
     */
    private fun ASTNode.hasValidPosition(): Boolean = lineNumber > 0 && columnNumber > 0 &&
        lastLineNumber > 0 && lastColumnNumber > 0

    /**
     * Check if a position is within an AST node's range
     */
    fun isPositionInNode(lspPosition: Position, node: ASTNode): Boolean {
        if (!node.hasValidPosition()) return false

        val (astLine, astColumn) = lspToAst(lspPosition)

        return when {
            astLine < node.lineNumber -> false
            astLine > node.lastLineNumber -> false
            astLine == node.lineNumber && astColumn < node.columnNumber -> false
            astLine == node.lastLineNumber && astColumn > node.lastColumnNumber -> false
            else -> true
        }
    }

    /**
     * Compare two positions (LSP coordinates)
     */
    fun comparePositions(pos1: Position, pos2: Position): Int {
        val lineDiff = pos1.line.compareTo(pos2.line)
        return if (lineDiff != 0) lineDiff else pos1.character.compareTo(pos2.character)
    }

    /**
     * Calculate the size of a range for sorting purposes
     */
    fun calculateRangeSize(range: Range): Int {
        val lineSpan = range.end.line - range.start.line
        val charSpan = if (lineSpan == 0) {
            range.end.character - range.start.character
        } else {
            lineSpan * COLUMN_WEIGHT_MULTIPLIER + range.end.character
        }
        return lineSpan * LINE_WEIGHT_MULTIPLIER + charSpan
    }
}
