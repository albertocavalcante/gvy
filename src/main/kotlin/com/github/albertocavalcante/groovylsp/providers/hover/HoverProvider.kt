package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.ast.findNodeAt
import com.github.albertocavalcante.groovylsp.ast.isHoverable
import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.hover.createHoverFor
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.InvalidPositionException
import com.github.albertocavalcante.groovylsp.errors.NodeNotFoundAtPositionException
import com.github.albertocavalcante.groovylsp.errors.SymbolResolutionException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
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
class HoverProvider(private val compilationService: GroovyCompilationService) {
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
            val hoverNode = resolveHoverNode(documentUri, position)

            if (hoverNode == null) {
                logger.debug("No hover node resolved for $uri at ${position.line}:${position.character}")
                return@withContext null
            }

            logger.debug(
                "Found hover node: ${hoverNode.javaClass.simpleName} at ${hoverNode.lineNumber}:${hoverNode.columnNumber}",
            )

            // Only provide hover for hoverable nodes
            if (!hoverNode.isHoverable()) {
                logger.debug("Node ${hoverNode.javaClass.simpleName} is not hoverable")
                return@withContext null
            }

            logger.debug("Creating hover content for hoverable node: ${hoverNode.javaClass.simpleName}")
            // Create hover using DSL
            val hover = createHoverFor(hoverNode).getOrNull()
            if (hover == null) {
                logger.debug("Hover content creation returned null")
            } else {
                logger.debug("Successfully created hover content")
            }
            hover
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

    /**
     * Resolve the appropriate node for hover display.
     */
    private fun resolveHoverNode(documentUri: URI, position: Position): ASTNode? {
        val astVisitor = compilationService.getAstVisitor(documentUri)
        val symbolTable = compilationService.getSymbolTable(documentUri)

        logger.debug("=== HOVER RESOLUTION START ===")
        logger.debug("Document URI: $documentUri")
        logger.debug("Position: Line ${position.line}, Char ${position.character}")
        logger.debug("AST Visitor available: ${astVisitor != null}")
        logger.debug("Symbol Table available: ${symbolTable != null}")

        if (astVisitor != null) {
            val totalNodes = astVisitor.getAllNodes().size
            val nodesForFile = astVisitor.getNodes(documentUri).size
            logger.debug("AST Visitor stats - Total nodes: $totalNodes, Nodes for this file: $nodesForFile")
        }

        val nodeAtPosition = findNodeAtPosition(documentUri, position, astVisitor)

        // If no node found at exact position, check if we're hovering near an import statement
        if (nodeAtPosition == null && astVisitor != null) {
            val importNode = findNearbyImportNode(documentUri, position, astVisitor)
            if (importNode != null) {
                logger.debug("Found nearby import node: ${importNode.className}")
                return resolveNodeWithContext(importNode, astVisitor, symbolTable ?: SymbolTable())
            }
        }

        if (nodeAtPosition == null) {
            logger.debug("No node found at position ${position.line}:${position.character}")
            logger.debug("=== HOVER RESOLUTION END ===")
            return null
        }

        logger.debug("Found node: ${nodeAtPosition.javaClass.simpleName}")
        logger.debug("Node text: ${nodeAtPosition.text}")
        logger.debug("Node position: Line ${nodeAtPosition.lineNumber}, Col ${nodeAtPosition.columnNumber}")

        // Check if node's URI matches expected
        if (astVisitor != null) {
            val nodeUri = astVisitor.getUri(nodeAtPosition)
            if (nodeUri != documentUri) {
                logger.warn("⚠️ NODE URI MISMATCH! Expected: $documentUri, Got: $nodeUri")
            } else {
                logger.debug("✓ Node URI matches document URI")
            }
        }

        val resolvedNode = if (astVisitor != null && symbolTable != null) {
            resolveNodeWithContext(nodeAtPosition, astVisitor, symbolTable)
        } else {
            logger.debug(
                "Using fallback resolution (missing astVisitor=${astVisitor == null} " +
                    "or symbolTable=${symbolTable == null})",
            )
            resolveFallbackNode(nodeAtPosition)
        }

        logger.debug("=== HOVER RESOLUTION END ===")
        return resolvedNode
    }

    private fun findNodeAtPosition(documentUri: URI, position: Position, astVisitor: AstVisitor?): ASTNode? =
        if (astVisitor != null) {
            astVisitor.getNodeAt(documentUri, position)
        } else {
            val ast = compilationService.getAst(documentUri) as? ModuleNode
            ast?.findNodeAt(position.line, position.character)
        }

    private fun resolveNodeWithContext(
        nodeAtPosition: ASTNode,
        astVisitor: AstVisitor,
        symbolTable: SymbolTable,
    ): ASTNode = when (nodeAtPosition) {
        is ConstantExpression -> resolveConstantExpression(nodeAtPosition, astVisitor, symbolTable)
        is VariableExpression -> resolveVariableExpression(nodeAtPosition, astVisitor, symbolTable)
        is MethodCallExpression -> resolveMethodCallExpression(nodeAtPosition)
        is ImportNode -> resolveImportNode(nodeAtPosition, astVisitor, symbolTable)
        is ClassNode -> resolveClassNode(nodeAtPosition, astVisitor, symbolTable)
        else -> nodeAtPosition
    }

    private fun resolveConstantExpression(
        nodeAtPosition: ConstantExpression,
        astVisitor: AstVisitor,
        symbolTable: SymbolTable,
    ): ASTNode {
        val parent = astVisitor.getParent(nodeAtPosition)

        // Check if this constant is part of a method call
        if (parent is MethodCallExpression && parent.method == nodeAtPosition) {
            logger.debug("Found method name '${nodeAtPosition.text}' in method call expression")
            return parent
        }

        // Check if this constant might be part of an import statement
        // In import statements, class names often appear as constant expressions
        val constantText = nodeAtPosition.text
        if (constantText != null && constantText.matches(Regex("[A-Z][a-zA-Z0-9]*"))) {
            // This looks like a class name, try to find it in the symbol table
            val resolvedClass = findClassInSymbolTable(constantText, symbolTable, astVisitor)
            if (resolvedClass != null) {
                logger.debug("Resolved constant '$constantText' to class definition")
                return resolvedClass
            }
        }

        return nodeAtPosition
    }

    private fun resolveVariableExpression(
        nodeAtPosition: VariableExpression,
        astVisitor: AstVisitor,
        symbolTable: SymbolTable,
    ): ASTNode {
        val parent = astVisitor.getParent(nodeAtPosition)
        return if (isVariableDeclaration(parent, nodeAtPosition)) {
            nodeAtPosition
        } else {
            nodeAtPosition.resolveToDefinition(astVisitor, symbolTable, strict = false) ?: nodeAtPosition
        }
    }

    private fun isVariableDeclaration(parent: ASTNode?, nodeAtPosition: VariableExpression): Boolean =
        parent is org.codehaus.groovy.ast.expr.DeclarationExpression &&
            parent.leftExpression == nodeAtPosition

    private fun resolveMethodCallExpression(nodeAtPosition: MethodCallExpression): ASTNode {
        logger.debug("Found method call expression: ${nodeAtPosition.method.text}")
        return nodeAtPosition
    }

    private fun resolveFallbackNode(nodeAtPosition: ASTNode): ASTNode = when (nodeAtPosition) {
        is ConstantExpression -> nodeAtPosition
        else -> nodeAtPosition
    }

    /**
     * Create hover content using the DSL.
     */
    private fun createHoverContent(node: ASTNode): Hover? {
        logger.debug("Creating hover content for node: ${node.javaClass.simpleName}")
        logger.debug("Node is hoverable: ${node.isHoverable()}")

        val result = createHoverFor(node)
        if (result.isFailure) {
            logger.debug("Hover creation failed: ${result.exceptionOrNull()?.message}")
        } else {
            logger.debug("Hover creation succeeded")
        }

        return result.getOrNull()
    }

    /**
     * Resolve an ImportNode to the actual class being imported.
     */
    private fun resolveImportNode(importNode: ImportNode, astVisitor: AstVisitor, symbolTable: SymbolTable): ASTNode {
        val className = importNode.className
        logger.debug("Resolving import for class: $className")

        // Try to find the class definition in the symbol table
        val resolvedClass = findClassInSymbolTable(className, symbolTable, astVisitor)
        return resolvedClass ?: importNode
    }

    /**
     * Resolve a ClassNode (might already be the definition we want).
     */
    private fun resolveClassNode(
        classNode: ClassNode,
        @Suppress("UnusedParameter") astVisitor: AstVisitor,
        @Suppress("UnusedParameter") symbolTable: SymbolTable,
    ): ASTNode {
        logger.debug("Resolving class node: ${classNode.name}")
        return classNode // ClassNode is already a definition
    }

    /**
     * Find a class by name in the symbol table and AST visitor.
     */
    private fun findClassInSymbolTable(
        className: String,
        @Suppress("UnusedParameter") symbolTable: SymbolTable,
        astVisitor: AstVisitor,
    ): ClassNode? {
        // Try to find by simple class name first
        val allClassNodes = astVisitor.getAllClassNodes()

        // Look for exact name match
        val exactMatch = allClassNodes.find { it.nameWithoutPackage == className }
        if (exactMatch != null) {
            logger.debug("Found exact class match for '$className': ${exactMatch.name}")
            return exactMatch
        }

        // Look for full name match (in case className includes package)
        val fullNameMatch = allClassNodes.find { it.name == className }
        if (fullNameMatch != null) {
            logger.debug("Found full name class match for '$className': ${fullNameMatch.name}")
            return fullNameMatch
        }

        logger.debug("No class found for '$className' in ${allClassNodes.size} available classes")
        return null
    }

    /**
     * Find an import node that's near the given position.
     * This handles cases where the hover position is on the class name within an import statement.
     */
    private fun findNearbyImportNode(documentUri: URI, position: Position, astVisitor: AstVisitor): ImportNode? {
        val allNodes = astVisitor.getAllNodes()

        // Look for ImportNodes in the same file
        val importNodes = allNodes.filterIsInstance<ImportNode>().filter { node ->
            astVisitor.getUri(node) == documentUri
        }

        // Check if the position is on the same line as any import (with some tolerance)
        val targetLine = position.line + 1 // Convert LSP 0-indexed to AST 1-indexed
        val importOnSameLine = importNodes.find { importNode ->
            importNode.lineNumber == targetLine
        }

        if (importOnSameLine != null) {
            logger.debug("Found import on same line $targetLine: ${importOnSameLine.className}")
            return importOnSameLine
        }

        // If no exact line match, check nearby lines (±1 line tolerance)
        val nearbyImport = importNodes.find { importNode ->
            kotlin.math.abs(importNode.lineNumber - targetLine) <= 1
        }

        if (nearbyImport != null) {
            logger.debug("Found nearby import within ±1 line of $targetLine: ${nearbyImport.className}")
        }

        return nearbyImport
    }
}
