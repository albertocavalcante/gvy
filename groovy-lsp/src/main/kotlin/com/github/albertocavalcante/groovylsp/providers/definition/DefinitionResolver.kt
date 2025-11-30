package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.errors.CircularReferenceException
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.SymbolNotFoundException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import com.github.albertocavalcante.groovylsp.errors.nodeNotFoundAtPosition
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.slf4j.LoggerFactory
import java.net.URI

class DefinitionResolver(
    private val astVisitor: GroovyAstModel,
    private val symbolTable: SymbolTable,
    private val compilationService: GroovyCompilationService? = null,
) {

    private val logger = LoggerFactory.getLogger(DefinitionResolver::class.java)

    /**
     * Find the definition of the symbol at the given position.
     * Throws specific exceptions for different failure scenarios.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed as domain errors
    fun findDefinitionAt(uri: URI, position: com.github.albertocavalcante.groovyparser.ast.types.Position): ASTNode? {
        logger.debug("Finding definition at $uri:${position.line}:${position.character}")

        return try {
            val targetNode = validateAndFindNode(uri, position)
            val definition = resolveDefinition(targetNode, uri, position)
            validateDefinition(definition, uri)
        } catch (e: GroovyLspException) {
            // Re-throw our specific exceptions
            logger.debug("Specific error finding definition: $e")
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error finding definition at $uri:${position.line}:${position.character}", e)
            throw createSymbolNotFoundException("unknown", uri, position)
        }
    }

    /**
     * Validate position and find the target node.
     */
    private fun validateAndFindNode(
        uri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): ASTNode {
        // Validate position
        if (position.line < 0 || position.character < 0) {
            handleValidationError("invalidPosition", uri, ValidationContext.PositionContext(position))
        }

        // Find the node at the given LSP position
        val targetNode = astVisitor.getNodeAt(uri, position)
            ?: handleValidationError("nodeNotFound", uri, ValidationContext.PositionContext(position))

        logger.debug("Found target node: ${targetNode.javaClass.simpleName}")
        return targetNode
    }

    /**
     * Resolve the target node to its definition.
     */
    private fun resolveDefinition(
        targetNode: ASTNode,
        uri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): ASTNode {
        val initialDefinition = try {
            targetNode.resolveToDefinition(astVisitor, symbolTable, strict = false)
        } catch (e: StackOverflowError) {
            logger.debug("Stack overflow during definition resolution, likely circular reference", e)
            handleResolutionError(e, targetNode, uri, position)
        } catch (e: IllegalArgumentException) {
            logger.debug("Invalid argument during definition resolution: ${e.message}", e)
            handleResolutionError(e, targetNode, uri, position)
        } catch (e: IllegalStateException) {
            logger.debug("Invalid state during definition resolution: ${e.message}", e)
            handleResolutionError(e, targetNode, uri, position)
        }

        // Refine ClassNode resolution: prefer local declaration over reference
        // If we found a ClassNode, check if there's a specific declaration in the AST with the same name.
        // This handles cases where resolveToDefinition returns a reference ClassNode (e.g. from a constructor call)
        // but the class is actually defined in the same file.
        val definition = if (initialDefinition is ClassNode) {
            astVisitor.getAllClassNodes().find { it.name == initialDefinition.name } ?: initialDefinition
        } else {
            initialDefinition
        }

        // Check if we need to try global lookup (e.g. for class references across files)
        if (shouldTryGlobalLookup(targetNode, definition)) {
            val globalDef = findGlobalDefinition(targetNode)
            if (globalDef != null) {
                logger.debug("Found global definition for ${targetNode.text}: ${globalDef.javaClass.simpleName}")
                return globalDef
            }
        }

        // FIXME: Filter out non-definition nodes that shouldn't be treated as symbols
        val filteredDefinition = when (definition) {
            is org.codehaus.groovy.ast.expr.ConstantExpression -> null // String literals aren't definitions
            else -> definition
        }

        return filteredDefinition ?: handleResolutionError(null, targetNode, uri, position)
    }

    /**
     * Check if we should attempt global lookup.
     * This is needed when local resolution returns the reference itself (common for ClassNode)
     * or when no definition was found.
     */
    private fun shouldTryGlobalLookup(targetNode: ASTNode, definition: ASTNode?): Boolean {
        // If no definition found locally, try global
        if (definition == null) return true

        // If definition is a ClassNode, check if it's a real declaration in the current file
        if (definition is ClassNode) {
            // Check if this specific ClassNode instance is one of the classes declared in the AST
            // If it's not in the list of declared classes, it's likely a reference/placeholder
            val isDeclaredInFile = astVisitor.getAllClassNodes().any {
                it === definition || it.name == definition.name
            }
            if (!isDeclaredInFile) {
                return true
            }
        }
        return false
    }

    /**
     * Attempt to find definition globally using the compilation service.
     */
    private fun findGlobalDefinition(targetNode: ASTNode): ASTNode? {
        if (compilationService == null) return null

        val className = when (targetNode) {
            is ClassNode -> targetNode.name
            is ConstructorCallExpression -> targetNode.type.name
            is ClassExpression -> targetNode.type.name
            else -> return null
        }

        logger.debug("Attempting global lookup for class: $className")

        // Search all symbol indices
        compilationService.getAllSymbolStorages().values.forEach { index ->
            index.getUris().forEach { uri ->
                // Look for Class symbol with matching name
                val symbol = index.find<Symbol.Class>(uri, className)
                if (symbol != null) {
                    // Found it! Load the AST node
                    val classNode = loadClassNodeFromAst(uri, className)
                    if (classNode != null) {
                        return classNode
                    } else {
                        logger.warn("Symbol found in index but ClassNode not found in AST for $className at $uri")
                    }
                }
            }
        }
        return null
    }

    private fun loadClassNodeFromAst(uri: URI, className: String): ASTNode? {
        val ast = compilationService?.getAst(uri) ?: return null
        // Find ClassNode in the AST
        return (ast as? ModuleNode)?.classes?.find { it.name == className }
    }

    /**
     * Validate the definition node has proper position information.
     */
    private fun validateDefinition(definition: ASTNode, uri: URI): ASTNode {
        // Make sure the definition has valid position information
        if (!definition.hasValidPosition()) {
            logger.debug("Definition node has invalid position information")
            handleValidationError("invalidDefinitionPosition", uri, ValidationContext.NodeContext(definition))
        }

        logger.debug(
            "Resolved to definition: ${definition.javaClass.simpleName} " +
                "at ${definition.lineNumber}:${definition.columnNumber}",
        )
        return definition
    }

    /**
     * Create a SymbolNotFoundException with consistent parameters.
     */
    private fun createSymbolNotFoundException(
        symbol: String,
        uri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ) = SymbolNotFoundException(symbol, uri, position.line, position.character)

    /**
     * Handle resolution errors by throwing appropriate exceptions.
     * Consolidates all exception throwing logic to reduce throw count.
     */
    @Suppress("ThrowsCount") // This method centralizes all throws to satisfy detekt
    private fun handleResolutionError(
        originalException: Throwable?,
        targetNode: ASTNode,
        uri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): Nothing = when (originalException) {
        is StackOverflowError -> throw CircularReferenceException(
            targetNode.javaClass.simpleName,
            listOf("resolveToDefinition", targetNode.toString()),
        )

        else -> throw createSymbolNotFoundException(targetNode.toString(), uri, position)
    }

    /**
     * Validation context for different error scenarios.
     */
    private sealed class ValidationContext {
        data class PositionContext(val position: com.github.albertocavalcante.groovyparser.ast.types.Position) :
            ValidationContext()

        data class NodeContext(val node: ASTNode) : ValidationContext()
    }

    /**
     * Handle validation errors by throwing appropriate exceptions.
     * Consolidates validation throws to reduce throw count.
     */
    @Suppress("ThrowsCount") // This method centralizes all throws to satisfy detekt
    private fun handleValidationError(errorType: String, uri: URI, context: ValidationContext): Nothing =
        when (context) {
            is ValidationContext.PositionContext -> handlePositionValidationError(errorType, uri, context.position)
            is ValidationContext.NodeContext -> handleNodeValidationError(errorType, uri, context.node)
        }

    private fun handlePositionValidationError(
        errorType: String,
        uri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): Nothing = when (errorType) {
        "invalidPosition" -> throw uri.invalidPosition(position.line, position.character, "Negative coordinates")
        "nodeNotFound" -> throw uri.nodeNotFoundAtPosition(position.line, position.character)
        else -> throw createSymbolNotFoundException("unknown", uri, position)
    }

    private fun handleNodeValidationError(errorType: String, uri: URI, node: ASTNode): Nothing = when (errorType) {
        "invalidDefinitionPosition" -> throw uri.invalidPosition(
            node.lineNumber,
            node.columnNumber,
            "Definition node has invalid position information",
        )

        else -> throw createSymbolNotFoundException(
            node.toString(),
            uri,
            com.github.albertocavalcante.groovyparser.ast.types.Position(node.lineNumber, node.columnNumber),
        )
    }

    /**
     * Find all targets at the given position for the specified target kinds.
     * Based on kotlin-lsp's getTargetsAtPosition pattern.
     */
    fun findTargetsAt(
        uri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
        targetKinds: Set<TargetKind>,
    ): List<ASTNode> {
        logger.debug("Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds")

        val targetNode = astVisitor.getNodeAt(uri, position)
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
