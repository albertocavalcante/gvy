package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.isReferenceableSymbol
import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.ast.safeName
import com.github.albertocavalcante.groovylsp.ast.safeRange
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory
import java.net.URI

class RenameProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {
    private val logger = LoggerFactory.getLogger(RenameProvider::class.java)

    suspend fun prepareRename(uri: String, position: Position): PrepareRenameResult? {
        val context = findContext(uri, position) ?: return null
        val target = context.targetNode

        return target.safeRange().getOrNull()?.let { range ->
            PrepareRenameResult(range, target.safeName() ?: "")
        }
    }

    suspend fun rename(uri: String, position: Position, newName: String): WorkspaceEdit {
        logger.info("Rename requested for $uri at ${position.line}:${position.character} -> $newName")
        val context = findContext(uri, position) ?: run {
            logger.warn("Rename aborted: unable to resolve context for $uri")
            return WorkspaceEdit(mutableMapOf())
        }

        if (context.targetVariable == null) {
            logger.warn(
                "Rename currently supports variable expressions only (target: ${context.targetNode.javaClass.simpleName})",
            )
            return WorkspaceEdit(mutableMapOf())
        }

        val edits = collectEdits(context, newName)

        return WorkspaceEdit().apply {
            changes = mutableMapOf(uri to edits.toMutableList())
        }
    }

    private fun collectEdits(context: RenameContext, newName: String): List<TextEdit> {
        val targetVariable = context.targetVariable ?: return emptyList()

        return context.visitor.getNodes(context.documentUri)
            .filterIsInstance<org.codehaus.groovy.ast.expr.VariableExpression>()
            .filter { node -> node.accessedVariable == targetVariable }
            .mapNotNull { node -> node.safeRange().getOrNull()?.let { TextEdit(it, newName) } }
    }

    private suspend fun findContext(uri: String, position: Position): RenameContext? {
        val documentUri = URI.create(uri)
        ensurePrepared(documentUri)
        val visitor = compilationService.getAstVisitor(documentUri) ?: return null
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return null

        val targetNode = visitor.getNodeAt(documentUri, position.line, position.character)
            ?.takeIf { it.isReferenceableSymbol() }
            ?: run {
                logger.warn("Rename aborted: no referenceable node at $position")
                return null
            }

        val definition = targetNode.resolveToDefinition(visitor, symbolTable, strict = false)
        val targetVariable = (targetNode as? org.codehaus.groovy.ast.expr.VariableExpression)?.accessedVariable

        return RenameContext(
            documentUri = documentUri,
            visitor = visitor,
            symbolTable = symbolTable,
            targetNode = targetNode,
            definitionNode = definition,
            targetVariable = targetVariable,
            position = position,
        )
    }

    private data class RenameContext(
        val documentUri: URI,
        val visitor: AstVisitor,
        val symbolTable: com.github.albertocavalcante.groovylsp.ast.SymbolTable,
        val targetNode: ASTNode,
        val definitionNode: ASTNode?,
        val targetVariable: org.codehaus.groovy.ast.Variable?,
        val position: Position,
    )

    private suspend fun ensurePrepared(uri: URI) {
        val hasVisitor = compilationService.getAstVisitor(uri) != null
        val hasSymbols = compilationService.getSymbolTable(uri) != null
        if (hasVisitor && hasSymbols) {
            return
        }

        val content = documentProvider.get(uri) ?: return
        compilationService.compile(uri, content)
    }
}
