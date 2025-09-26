package com.github.albertocavalcante.groovylsp.converters

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Converts AST nodes to LSP Location objects.
 * Handles position mapping and URI conversion for go-to-definition functionality.
 *
 * NB: Coordinate Systems
 * - Groovy AST: 1-based line and column (line 1 = first line, column 1 = first character)
 * - LSP Protocol: 0-based line and column (line 0 = first line, column 0 = first character)
 * - Conversion: groovyPos = lspPos + 1, lspPos = groovyPos - 1
 * - Invalid positions: Groovy uses -1 for synthetic/invalid nodes
 */
object LocationConverter {

    private val logger = LoggerFactory.getLogger(LocationConverter::class.java)

    /**
     * Convert an AST node to an LSP Location.
     * Returns null if the node has invalid position information.
     */
    fun nodeToLocation(node: ASTNode, visitor: AstVisitor): Location? {
        val uri = visitor.getUri(node)
        val range = nodeToRange(node)

        if (uri == null || range == null) {
            logger.debug(
                "No URI found for node: ${node.javaClass.simpleName}" +
                    if (range == null) " or no valid range" else "",
            )
            return null
        }

        return Location(uri.toString(), range)
    }

    /**
     * Convert an AST node to an LSP LocationLink.
     * Returns null if the node has invalid position information.
     */
    fun nodeToLocationLink(originNode: ASTNode, targetNode: ASTNode, visitor: AstVisitor): LocationLink? {
        val targetUri = visitor.getUri(targetNode)
        val originRange = nodeToRange(originNode)
        val targetRange = nodeToRange(targetNode)
        val targetSelectionRange = getSelectionRange(targetNode) ?: targetRange

        if (targetUri == null || originRange == null || targetRange == null) {
            logger.debug("Invalid URI or ranges for LocationLink")
            return null
        }

        return LocationLink().apply {
            this.originSelectionRange = originRange
            this.targetUri = targetUri.toString()
            this.targetRange = targetRange
            this.targetSelectionRange = targetSelectionRange
        }
    }

    /**
     * Convert an AST node to an LSP Range.
     * Returns null if the node has invalid position information.
     */
    fun nodeToRange(node: ASTNode): Range? {
        if (!hasValidPosition(node)) {
            return null
        }

        // Convert from 1-based AST positions to 0-based LSP positions
        val startLine = maxOf(0, node.lineNumber - 1)
        val startColumn = maxOf(0, node.columnNumber - 1)
        val endLine = maxOf(0, node.lastLineNumber - 1)
        val endColumn = maxOf(0, node.lastColumnNumber - 1)

        return Range(
            Position(startLine, startColumn),
            Position(endLine, endColumn),
        )
    }

    /**
     * Get a selection range for a node (typically the name identifier).
     * For nodes like methods or classes, this would be just the name part.
     */
    private fun getSelectionRange(node: ASTNode): Range? = when (node) {
        is org.codehaus.groovy.ast.MethodNode -> {
            // For methods, try to get just the method name range
            nodeToRange(node)?.let { fullRange ->
                // Approximate the method name position (this could be improved)
                Range(fullRange.start, Position(fullRange.start.line, fullRange.start.character + node.name.length))
            }
        }
        is org.codehaus.groovy.ast.ClassNode -> {
            // For classes, try to get just the class name range
            nodeToRange(node)?.let { fullRange ->
                // Approximate the class name position
                Range(
                    fullRange.start,
                    Position(
                        fullRange.start.line,
                        fullRange.start.character + node.nameWithoutPackage.length,
                    ),
                )
            }
        }
        is org.codehaus.groovy.ast.FieldNode -> {
            // For fields, use the field name
            nodeToRange(node)?.let { fullRange ->
                Range(fullRange.start, Position(fullRange.start.line, fullRange.start.character + node.name.length))
            }
        }
        is org.codehaus.groovy.ast.PropertyNode -> {
            // For properties, use the property name
            nodeToRange(node)?.let { fullRange ->
                Range(fullRange.start, Position(fullRange.start.line, fullRange.start.character + node.name.length))
            }
        }
        else -> nodeToRange(node)
    }

    /**
     * Check if a node has valid position information.
     * NB: Groovy AST uses 1-based indexing, but we need to handle edge cases
     * where some valid nodes might have 0 or -1 in column positions.
     */
    private fun hasValidPosition(node: ASTNode): Boolean = node.lineNumber >= 1 &&
        node.columnNumber >= 0 &&
        node.lastLineNumber >= 1 &&
        node.lastColumnNumber >= 0

    /**
     * Create a Location for a specific URI and range
     */
    fun createLocation(uri: URI, range: Range): Location = Location(uri.toString(), range)

    /**
     * Create a Location for a specific URI and position (single point)
     */
    fun createLocationAtPosition(uri: URI, line: Int, character: Int): Location {
        val position = Position(line, character)
        val range = Range(position, position)
        return Location(uri.toString(), range)
    }

    /**
     * Convert a list of AST nodes to a list of Locations
     */
    fun nodesToLocations(nodes: List<ASTNode>, visitor: AstVisitor): List<Location> = nodes.mapNotNull { node ->
        nodeToLocation(node, visitor)
    }

    /**
     * Convert a list of AST nodes to a list of LocationLinks
     */
    fun nodesToLocationLinks(originNode: ASTNode, targetNodes: List<ASTNode>, visitor: AstVisitor): List<LocationLink> =
        targetNodes.mapNotNull { targetNode ->
            nodeToLocationLink(originNode, targetNode, visitor)
        }
}
