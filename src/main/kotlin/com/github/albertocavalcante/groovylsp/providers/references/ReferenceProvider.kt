package com.github.albertocavalcante.groovylsp.providers.references

import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.LocationConverter
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for finding references to symbols in Groovy code.
 */
class ReferenceProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(ReferenceProvider::class.java)

    /**
     * Find all references to the symbol at the given position.
     *
     * @param uri The URI of the document
     * @param position The position in the document
     * @param includeDeclaration Whether to include the declaration in the results
     * @return Flow of locations where the symbol is referenced
     */
    fun provideReferences(uri: String, position: Position, includeDeclaration: Boolean): Flow<Location> = channelFlow {
        logger.debug("Finding references for $uri at ${position.line}:${position.character}")

        try {
            val documentUri = URI.create(uri)

            // Get AST visitor and symbol table for the document
            val visitor = compilationService.getAstVisitor(documentUri) ?: return@channelFlow
            val symbolTable = compilationService.getSymbolTable(documentUri) ?: return@channelFlow

            // Find the node at the position
            val targetNode = visitor.getNodeAt(documentUri, position.line, position.character)
            if (targetNode == null) {
                logger.debug("No node found at position")
                return@channelFlow
            }

            // Validate that the node is a referenceable symbol
            if (!targetNode.isReferenceableSymbol()) {
                logger.debug("Node at position is not a referenceable symbol: ${targetNode.javaClass.simpleName}")
                return@channelFlow
            }

            // Check if the target node itself is part of a declaration
            val targetIsDeclaration = when {
                targetNode is org.codehaus.groovy.ast.expr.VariableExpression -> {
                    val parent = visitor.getParent(targetNode)
                    parent is org.codehaus.groovy.ast.expr.DeclarationExpression && parent.leftExpression == targetNode
                }
                targetNode is org.codehaus.groovy.ast.Parameter -> true
                targetNode is org.codehaus.groovy.ast.MethodNode -> true
                targetNode is org.codehaus.groovy.ast.FieldNode -> true
                targetNode is org.codehaus.groovy.ast.PropertyNode -> true
                targetNode is org.codehaus.groovy.ast.ClassNode -> true
                else -> false
            }

            // Resolve the target node to its definition
            val definition = targetNode.resolveToDefinition(visitor, symbolTable, strict = false)
            if (definition == null) {
                logger.debug("Could not resolve definition for node")
                return@channelFlow
            }

            logger.debug(
                "Found definition: ${definition.javaClass.simpleName}, targetIsDeclaration: $targetIsDeclaration",
            )

            // Find all references to this definition
            findReferences(definition, visitor, symbolTable, includeDeclaration)
        } catch (e: GroovyLspException) {
            logger.error("LSP error finding references", e)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments for finding references", e)
        } catch (e: IllegalStateException) {
            logger.error("Invalid state while finding references", e)
        } catch (e: RuntimeException) {
            logger.error("Runtime error finding references", e)
        }
    }

    /**
     * Find all references to the given definition node.
     */
    private suspend fun ProducerScope<Location>.findReferences(
        definition: ASTNode,
        visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
        symbolTable: com.github.albertocavalcante.groovylsp.ast.SymbolTable,
        includeDeclaration: Boolean,
    ) {
        val emittedLocations = mutableSetOf<String>()

        // Find all references by checking all nodes
        visitor.getAllNodes()
            .filter { it.hasValidPosition() }
            .forEach { node ->
                val nodeDefinition = node.resolveToDefinition(visitor, symbolTable, strict = false)

                // Check if this node references the definition we're looking for
                if (nodeDefinition == definition) {
                    // Check if this node is part of a declaration context
                    val isPartOfDeclaration = when {
                        // Check if this is a declaration based on node type
                        node is org.codehaus.groovy.ast.Parameter -> true
                        node is org.codehaus.groovy.ast.MethodNode -> true
                        node is org.codehaus.groovy.ast.FieldNode -> true
                        node is org.codehaus.groovy.ast.PropertyNode -> true
                        node is org.codehaus.groovy.ast.ClassNode -> true
                        node is org.codehaus.groovy.ast.expr.VariableExpression -> {
                            // For variable expressions, check if it's part of a declaration
                            val parent = visitor.getParent(node)
                            val isDecl = parent is org.codehaus.groovy.ast.expr.DeclarationExpression &&
                                parent.leftExpression == node
                            if (isDecl) {
                                logger.debug("Found variable ${node.name} as part of declaration")
                            }
                            isDecl
                        }
                        else -> false
                    }

                    logger.debug(
                        "Node ${node.javaClass.simpleName} at ${node.lineNumber}:${node.columnNumber} - " +
                            "isPartOfDeclaration: $isPartOfDeclaration, includeDeclaration: $includeDeclaration",
                    )

                    // Only emit if we're including declarations OR this isn't part of a declaration
                    if (includeDeclaration || !isPartOfDeclaration) {
                        emitUniqueLocation(node, visitor, emittedLocations)
                    }
                }
            }
    }

    /**
     * Check if this node has valid position information for LSP.
     */
    private fun ASTNode.hasValidPosition(): Boolean = lineNumber > 0 && columnNumber > 0

    /**
     * Check if this node represents a referenceable symbol.
     */
    private fun ASTNode.isReferenceableSymbol(): Boolean = when (this) {
        is org.codehaus.groovy.ast.expr.VariableExpression -> true
        is org.codehaus.groovy.ast.expr.MethodCallExpression -> true
        is org.codehaus.groovy.ast.MethodNode -> true
        is org.codehaus.groovy.ast.FieldNode -> true
        is org.codehaus.groovy.ast.PropertyNode -> true
        is org.codehaus.groovy.ast.Parameter -> true
        is org.codehaus.groovy.ast.ClassNode -> true
        is org.codehaus.groovy.ast.expr.ConstructorCallExpression -> true
        is org.codehaus.groovy.ast.expr.PropertyExpression -> true
        is org.codehaus.groovy.ast.expr.ClassExpression -> true
        else -> false
    }

    /**
     * Emit a location for this node if it hasn't been seen before.
     */
    private suspend fun ProducerScope<Location>.emitUniqueLocation(
        node: ASTNode,
        visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
        seen: MutableSet<String>,
    ) {
        val location = LocationConverter.nodeToLocation(node, visitor) ?: return
        val key = "${location.uri}:${location.range.start.line}:${location.range.start.character}"
        if (seen.add(key)) {
            send(location)
        }
    }
}
