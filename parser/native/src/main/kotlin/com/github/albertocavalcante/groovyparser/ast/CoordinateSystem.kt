package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.slf4j.LoggerFactory

/**
 * THE SINGLE SOURCE OF TRUTH for all coordinate system operations in the Groovy LSP.
 *
 * **Coordinate Systems:**
 * - **LSP Protocol**: 0-based indexing (line 0 = first line, column 0 = first character)
 * - **Groovy AST**: 1-based indexing (line 1 = first line, column 1 = first character)
 *
 * **IMPORTANT RULES:**
 * 1. ALL coordinate conversions MUST go through this class
 * 2. Direct +1/-1 coordinate manipulation is FORBIDDEN elsewhere
 * 3. Position checking MUST use the methods in this class
 * 4. Use type-safe wrappers to prevent mixing coordinate systems
 *
 * This class eliminates the coordinate system bugs that plagued the codebase by:
 * - Providing a single, well-tested implementation
 * - Making coordinate systems explicit in the type system
 * - Preventing accidental mixing of LSP and Groovy coordinates
 *
 * TODO: FUTURE ARCHITECTURE - Integrate SafePosition's sophisticated type safety
 * The NodeExtensions.kt file contains a more advanced coordinate system using:
 * - LineNumber/ColumnNumber value classes for stronger typing
 * - GroovyParserResult monad for error handling
 * - SafePosition with validation and functional composition
 *
 * Long-term plan:
 * 1. Merge SafePosition's Result-based error handling into this class
 * 2. Adopt LineNumber/ColumnNumber value classes for all coordinate passing
 * 3. Use Result<T> pattern for all coordinate operations that can fail
 * 4. Deprecate and remove the parallel SafePosition implementation
 *
 * This will give us both centralization AND sophisticated type safety.
 */
object CoordinateSystem {

    private val logger = LoggerFactory.getLogger(CoordinateSystem::class.java)

    /**
     * Type-safe wrapper for LSP coordinates (0-based).
     *
     * TODO: Consider adopting SafePosition's approach with value classes:
     * - value class LineNumber(val value: Int) with validation
     * - value class ColumnNumber(val value: Int) with validation
     * This would prevent accidental Int usage and provide compile-time safety.
     */
    data class LspPosition(val line: Int, val character: Int) {
        companion object {
            fun from(position: Position) = LspPosition(position.line, position.character)
            fun from(line: Int, character: Int) = LspPosition(line, character)
        }

        fun toGroovy(): GroovyPosition = GroovyPosition(line + 1, character + 1)
        fun toLsp4j(): Position = Position(line, character)
    }

    /**
     * Type-safe wrapper for Groovy AST coordinates (1-based).
     *
     * TODO: Add validation in companion object factory methods:
     * - Reject negative values
     * - Return Result<GroovyPosition> for safe construction
     * - Follow SafePosition's pattern for error handling
     */
    data class GroovyPosition(val line: Int, val column: Int) {
        companion object {
            fun from(line: Int, column: Int) = GroovyPosition(line, column)
            fun fromNode(node: ASTNode) = GroovyPosition(node.lineNumber, node.columnNumber)
        }

        fun toLsp(): LspPosition = LspPosition(line - 1, column - 1)
    }

    /**
     * Represents a range in LSP coordinates.
     */
    data class LspRange(val start: LspPosition, val end: LspPosition)

    /**
     * Represents a range in Groovy coordinates.
     */
    data class GroovyRange(val start: GroovyPosition, val end: GroovyPosition) {
        fun toLsp(): LspRange = LspRange(start.toLsp(), end.toLsp())
    }

    // ===========================================
    // COORDINATE CONVERSION METHODS
    // ===========================================

    /**
     * Convert LSP position to Groovy position.
     */
    fun lspToGroovy(lspLine: Int, lspCharacter: Int): GroovyPosition = GroovyPosition(lspLine + 1, lspCharacter + 1)

    /**
     * Convert LSP position to Groovy position.
     */
    fun lspToGroovy(position: Position): GroovyPosition = lspToGroovy(position.line, position.character)

    /**
     * Convert Groovy position to LSP position.
     */
    fun groovyToLsp(groovyLine: Int, groovyColumn: Int): LspPosition = LspPosition(groovyLine - 1, groovyColumn - 1)

    // ===========================================
    // NODE POSITION VALIDATION
    // ===========================================

    /**
     * Check if an AST node has valid position information.
     * Groovy uses -1 or 0 for synthetic/invalid nodes.
     *
     * TODO: Return Result<Unit> or ValidationResult to provide error details
     * instead of just boolean. This would help debugging position issues.
     */
    fun isValidNodePosition(node: ASTNode): Boolean = node.lineNumber > 0 &&
        node.columnNumber > 0 &&
        node.lastLineNumber > 0 &&
        node.lastColumnNumber > 0

    /**
     * Get the range of an AST node in LSP coordinates.
     * Returns null if the node has invalid position information.
     *
     * TODO: Return Result<LspRange> instead of nullable
     * This would align with SafePosition's error handling approach
     * and provide better error messages when positions are invalid.
     */
    fun getNodeLspRange(node: ASTNode): LspRange? {
        if (!isValidNodePosition(node)) return null

        val start = groovyToLsp(node.lineNumber, node.columnNumber)
        val end = groovyToLsp(node.lastLineNumber, node.lastColumnNumber)
        return LspRange(start, end)
    }

    /**
     * Get the range of an AST node in Groovy coordinates.
     * Returns null if the node has invalid position information.
     */
    fun getNodeGroovyRange(node: ASTNode): GroovyRange? {
        if (!isValidNodePosition(node)) return null

        val start = GroovyPosition(node.lineNumber, node.columnNumber)
        val end = GroovyPosition(node.lastLineNumber, node.lastColumnNumber)
        return GroovyRange(start, end)
    }

    // ===========================================
    // POSITION CONTAINMENT - THE CORE LOGIC
    // ===========================================

    /**
     * THE definitive position containment check.
     * This is the ONLY place in the codebase where position containment logic exists.
     *
     * @param node The AST node to check
     * @param lspPosition The position in LSP coordinates (0-based)
     * @return true if the node contains the position
     */
    fun nodeContainsPosition(node: ASTNode, lspPosition: LspPosition): Boolean =
        nodeContainsPosition(node, lspPosition.line, lspPosition.character)

    /**
     * THE definitive position containment check with LSP coordinates.
     *
     * @param node The AST node to check
     * @param lspLine Line number in LSP coordinates (0-based)
     * @param lspCharacter Character position in LSP coordinates (0-based)
     * @return true if the node contains the position
     */
    fun nodeContainsPosition(node: ASTNode, lspLine: Int, lspCharacter: Int): Boolean {
        // Convert to Groovy coordinates for comparison with node positions
        val groovyPosition = lspToGroovy(lspLine, lspCharacter)
        return nodeContainsGroovyPosition(node, groovyPosition)
    }

    /**
     * THE definitive position containment check with Position object.
     */
    fun nodeContainsPosition(node: ASTNode, position: Position): Boolean =
        nodeContainsPosition(node, position.line, position.character)

    /**
     * Position containment check that tolerates missing end coordinates.
     *
     * Uses token length hints when lastColumnNumber is unavailable to widen the match.
     * This is intended for nodes where Groovy only provides start coordinates.
     */
    fun nodeContainsPositionRelaxed(
        node: ASTNode,
        lspLine: Int,
        lspCharacter: Int,
        tokenLengthHint: ((ASTNode) -> Int?)? = null,
    ): Boolean {
        if (node.lineNumber <= 0 || node.columnNumber <= 0) {
            return false
        }

        val groovyPos = lspToGroovy(lspLine, lspCharacter)
        val endLine = relaxedEndLine(node)
        val endColumn = relaxedEndColumn(node, tokenLengthHint)

        val nodeStart = GroovyPosition(node.lineNumber, node.columnNumber)
        val nodeEnd = GroovyPosition(endLine, endColumn)

        return containsGroovyPositionInRange(groovyPos, nodeStart, nodeEnd)
    }

    /**
     * Position containment check using Groovy coordinates.
     * This is where the actual containment logic lives.
     */
    private fun nodeContainsGroovyPosition(node: ASTNode, groovyPos: GroovyPosition): Boolean {
        // Check if the node has valid position information
        if (!isValidNodePosition(node)) {
            logger.debug("Node ${node.javaClass.simpleName} has invalid position information")
            return false
        }

        val nodeStart = GroovyPosition(node.lineNumber, node.columnNumber)
        val nodeEnd = GroovyPosition(node.lastLineNumber, node.lastColumnNumber)

        logger.debug("Checking if Groovy position $groovyPos is within node range $nodeStart to $nodeEnd")

        // Position containment logic using Groovy 1-based coordinates
        val result = containsGroovyPositionInRange(groovyPos, nodeStart, nodeEnd)

        if (result) {
            logger.debug("✓ Position $groovyPos is within node ${node.javaClass.simpleName}")
        } else {
            logger.debug("✗ Position $groovyPos is outside node ${node.javaClass.simpleName}")
        }

        return result
    }

    // ===========================================
    // DEBUGGING AND UTILITIES
    // ===========================================
}

private fun relaxedEndLine(node: ASTNode): Int = if (node.lastLineNumber > 0) node.lastLineNumber else node.lineNumber

private fun relaxedEndColumn(node: ASTNode, tokenLengthHint: ((ASTNode) -> Int?)?): Int {
    if (node.lastColumnNumber > 0) {
        return node.lastColumnNumber
    }

    val tokenLen = tokenLengthHint?.invoke(node) ?: 0
    return if (tokenLen > 0) {
        node.columnNumber + tokenLen - 1
    } else {
        node.columnNumber + 1
    }
}

private fun containsGroovyPositionInRange(
    groovyPos: CoordinateSystem.GroovyPosition,
    nodeStart: CoordinateSystem.GroovyPosition,
    nodeEnd: CoordinateSystem.GroovyPosition,
): Boolean = when {
    groovyPos.line < nodeStart.line || groovyPos.line > nodeEnd.line -> false
    nodeStart.line == nodeEnd.line -> groovyPos.column in nodeStart.column..nodeEnd.column
    groovyPos.line == nodeStart.line -> groovyPos.column >= nodeStart.column
    groovyPos.line == nodeEnd.line -> groovyPos.column <= nodeEnd.column
    else -> true
}
