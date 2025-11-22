package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.dsl.hover.createHoverFor
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.InvalidPositionException
import com.github.albertocavalcante.groovylsp.errors.NodeNotFoundAtPositionException
import com.github.albertocavalcante.groovylsp.errors.SymbolResolutionException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.groovyparser.ast.isHoverable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI

/**
 * Kotlin-idiomatic hover provider for Groovy symbols.
 * Uses coroutines, extension functions, and null safety for clean async processing.
 */
class HoverProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {
    private val logger = LoggerFactory.getLogger(HoverProvider::class.java)

    /**
     * Provide hover information for the symbol at the given position.
     * Returns null if no hover information is available.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    suspend fun provideHover(uri: String, position: Position): Hover? = withContext(Dispatchers.Default) {
        try {
            logger.debug("Providing hover for $uri at ${position.line}:${position.character}")

            val documentUri = URI.create(uri)
            ensureAstPrepared(documentUri)
            val groovyPosition = position.toGroovyPosition()

            val hoverNode = resolveHoverNode(documentUri, groovyPosition) ?: return@withContext null

            // Only provide hover for hoverable nodes
            if (!hoverNode.isHoverable()) {
                return@withContext null
            }

            // Create hover using DSL
            createHoverContent(hoverNode)
        } catch (e: NodeNotFoundAtPositionException) {
            logger.debug("No node found at position for hover: $e")
            null
        } catch (e: InvalidPositionException) {
            logger.warn("Invalid position for hover: $e")
            null
        } catch (e: SymbolResolutionException) {
            logger.debug("Symbol resolution failed for hover: $e")
            null
        } catch (e: IllegalStateException) {
            logger.error("Error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        } catch (e: IllegalArgumentException) {
            val documentUri = URI.create(uri)
            val specificException = documentUri.invalidPosition(
                position.line,
                position.character,
                e.message ?: "Invalid arguments",
            )
            logger.error("Invalid arguments for hover: $specificException", e)
            null
        } catch (e: GroovyLspException) {
            logger.error("LSP error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        } catch (e: IOException) {
            logger.error("I/O error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        } catch (e: Exception) {
            logger.error("Unexpected error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        }
    }

    private suspend fun ensureAstPrepared(documentUri: URI) {
        val hasAst = compilationService.getAst(documentUri) != null
        val hasVisitor = compilationService.getAstVisitor(documentUri) != null

        if (hasAst && hasVisitor) {
            return
        }

        val content = documentProvider.get(documentUri) ?: return

        runCatching {
            compilationService.compile(documentUri, content)
        }.onFailure { error ->
            logger.debug("HoverProvider: failed to compile $documentUri before hover", error)
        }
    }

    /**
     * Resolve the appropriate node for hover display.
     */
    private fun resolveHoverNode(
        documentUri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): ASTNode? {
        val astVisitor = compilationService.getAstVisitor(documentUri)
        val symbolTable = compilationService.getSymbolTable(documentUri)

        // 1. Find the node at position
        val nodeAtPosition = if (astVisitor != null) {
            astVisitor.getNodeAt(documentUri, position)
        } else {
            val ast = compilationService.getAst(documentUri) as? ModuleNode
            ast?.findNodeAt(position.line, position.character)
        } ?: return null

        logger.debug("Found node at position: ${nodeAtPosition.javaClass.simpleName}")

        // 2. If we have visitor/symbol table, try enhanced resolution
        if (astVisitor != null && symbolTable != null) {
            return resolveWithVisitor(nodeAtPosition, astVisitor, symbolTable)
        }

        // 3. Fallback resolution
        return resolveFallback(nodeAtPosition, documentUri)
    }

    private fun resolveWithVisitor(
        node: ASTNode,
        visitor: com.github.albertocavalcante.groovyparser.ast.AstVisitor,
        symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
    ): ASTNode = when (node) {
        is VariableExpression -> resolveVariable(node, visitor, symbolTable)
        is ConstantExpression -> resolveConstant(node, visitor)
        else -> node
    }

    private fun resolveVariable(
        node: VariableExpression,
        visitor: com.github.albertocavalcante.groovyparser.ast.AstVisitor,
        symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
    ): ASTNode {
        // If it's a declaration, show the node itself
        val parent = visitor.getParent(node)
        if (parent is org.codehaus.groovy.ast.expr.DeclarationExpression && parent.leftExpression == node) {
            return node
        }

        // Otherwise resolve to definition
        return node.resolveToDefinition(visitor, symbolTable, strict = false) ?: node
    }

    private fun resolveConstant(
        node: ConstantExpression,
        visitor: com.github.albertocavalcante.groovyparser.ast.AstVisitor,
    ): ASTNode {
        val parent = visitor.getParent(node)
        if (parent is MethodCallExpression && parent.method == node) {
            return parent
        }
        return node
    }

    private fun resolveFallback(node: ASTNode, documentUri: URI): ASTNode {
        if (node is ConstantExpression) {
            val parent = compilationService.getAstVisitor(documentUri)?.getParent(node)
            if (parent is MethodCallExpression && parent.method == node) {
                return parent
            }
        }
        return node
    }

    /**
     * Create hover content using the DSL.
     */
    private fun createHoverContent(node: ASTNode): Hover? = createHoverFor(node).getOrNull()
}
