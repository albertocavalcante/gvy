package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.errors.CircularReferenceException
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.SymbolNotFoundException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import com.github.albertocavalcante.groovylsp.errors.nodeNotFoundAtPosition
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Resolves symbols to their definitions using AST analysis.
 * Combines patterns from fork-groovy-language-server and kotlin-lsp.
 */
class DefinitionResolver(private val astVisitor: AstVisitor, private val symbolTable: SymbolTable) {

    private val logger = LoggerFactory.getLogger(DefinitionResolver::class.java)

    /**
     * Find the definition of the symbol at the given position.
     * Throws specific exceptions for different failure scenarios.
     */
    fun findDefinitionAt(uri: URI, position: Position): ASTNode? {
        logger.debug("Finding definition at $uri:${position.line}:${position.character}")

        try {
            // Validate position
            if (position.line < 0 || position.character < 0) {
                throw uri.invalidPosition(position.line, position.character, "Negative coordinates")
            }

            // Find the node at the given LSP position
            val targetNode = astVisitor.getNodeAt(uri, position)
                ?: throw uri.nodeNotFoundAtPosition(position.line, position.character)

            logger.debug("Found target node: ${targetNode.javaClass.simpleName}")

            // Try to resolve to definition
            val definition = try {
                targetNode.resolveToDefinition(astVisitor, symbolTable, strict = true)
            } catch (e: StackOverflowError) {
                throw CircularReferenceException(
                    targetNode.javaClass.simpleName,
                    listOf("resolveToDefinition", targetNode.toString()),
                )
            } catch (e: IllegalArgumentException) {
                throw SymbolNotFoundException(
                    targetNode.toString(),
                    uri,
                    position.line,
                    position.character,
                )
            } catch (e: IllegalStateException) {
                throw SymbolNotFoundException(
                    targetNode.toString(),
                    uri,
                    position.line,
                    position.character,
                )
            } catch (e: RuntimeException) {
                throw SymbolNotFoundException(
                    targetNode.toString(),
                    uri,
                    position.line,
                    position.character,
                )
            }

            if (definition == null) {
                throw SymbolNotFoundException(
                    targetNode.toString(),
                    uri,
                    position.line,
                    position.character,
                )
            }

            // Make sure the definition has valid position information
            if (!definition.hasValidPosition()) {
                logger.debug("Definition node has invalid position information")
                throw uri.invalidPosition(
                    definition.lineNumber,
                    definition.columnNumber,
                    "Definition node has invalid position information",
                )
            }

            logger.debug(
                "Resolved to definition: ${definition.javaClass.simpleName} " +
                    "at ${definition.lineNumber}:${definition.columnNumber}",
            )
            return definition
        } catch (e: GroovyLspException) {
            // Re-throw our specific exceptions
            logger.debug("Specific error finding definition: $e")
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments finding definition at $uri:${position.line}:${position.character}", e)
            throw SymbolNotFoundException(
                "unknown",
                uri,
                position.line,
                position.character,
            )
        } catch (e: IllegalStateException) {
            logger.error("Invalid state finding definition at $uri:${position.line}:${position.character}", e)
            throw SymbolNotFoundException(
                "unknown",
                uri,
                position.line,
                position.character,
            )
        } catch (e: RuntimeException) {
            logger.error("Runtime error finding definition at $uri:${position.line}:${position.character}", e)
            throw SymbolNotFoundException(
                "unknown",
                uri,
                position.line,
                position.character,
            )
        }
    }

    /**
     * Find all targets at the given position for the specified target kinds.
     * Based on kotlin-lsp's getTargetsAtPosition pattern.
     */
    fun findTargetsAt(uri: URI, position: Position, targetKinds: Set<TargetKind>): List<ASTNode> {
        logger.debug("Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds")

        val targetNode = astVisitor.getNodeAt(uri, position.line, position.character)
            ?: return emptyList()

        val results = mutableListOf<ASTNode>()

        if (TargetKind.REFERENCE in targetKinds) {
            // If looking for references, return the node itself if it's a reference
            if (targetNode.isReference()) {
                results.add(targetNode)
            }
        }

        if (TargetKind.DECLARATION in targetKinds) {
            // If looking for declarations, try to resolve to definition
            val definition = targetNode.resolveToDefinition(astVisitor, symbolTable, strict = false)
            if (definition != null && definition != targetNode && definition.hasValidPosition()) {
                results.add(definition)
            }
        }

        logger.debug("Found ${results.size} targets")
        return results
    }

    /**
     * Check if a node represents a reference (not a declaration)
     */
    private fun ASTNode.isReference(): Boolean = when (this) {
        is org.codehaus.groovy.ast.expr.VariableExpression -> true
        is org.codehaus.groovy.ast.expr.MethodCallExpression -> true
        is org.codehaus.groovy.ast.expr.ConstructorCallExpression -> true
        is org.codehaus.groovy.ast.expr.PropertyExpression -> true
        else -> false
    }

    /**
     * Check if the node has valid position information
     */
    private fun ASTNode.hasValidPosition(): Boolean =
        lineNumber > 0 && columnNumber > 0 && lastLineNumber > 0 && lastColumnNumber > 0

    /**
     * Get statistics about the resolver state
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "symbolTableStats" to symbolTable.getStatistics(),
        "totalNodes" to astVisitor.getAllNodes().size,
        "totalClassNodes" to astVisitor.getAllClassNodes().size,
    )
}
