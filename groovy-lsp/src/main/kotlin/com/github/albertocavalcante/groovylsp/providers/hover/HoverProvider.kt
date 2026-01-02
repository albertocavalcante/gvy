package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.dsl.hover.createHoverFor
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.InvalidPositionException
import com.github.albertocavalcante.groovylsp.errors.NodeNotFoundAtPositionException
import com.github.albertocavalcante.groovylsp.errors.SymbolResolutionException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import com.github.albertocavalcante.groovylsp.providers.completion.JenkinsStepCompletionProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.groovyparser.ast.isHoverable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
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
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
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
    private val sourceNavigator: SourceNavigator? = null,
) {
    private val logger = LoggerFactory.getLogger(HoverProvider::class.java)

    // Documentation provider for extracting groovydoc - use shared instance for cache consistency
    private val documentationProvider = com.github.albertocavalcante.groovylsp.documentation.DocumentationProvider
        .getInstance(documentProvider)

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

            // Create hover using DSL with documentation
            createHoverContent(hoverNode, documentUri)
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
        val hasVisitor = compilationService.getAstModel(documentUri) != null

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
        val astVisitor = compilationService.getAstModel(documentUri)
        val symbolTable = compilationService.getSymbolTable(documentUri)

        // 1. Find the node at position
        val nodeAtPosition = if (astVisitor != null) {
            astVisitor.getNodeAt(documentUri, position)
        } else {
            val ast = compilationService.getAst(documentUri) as? ModuleNode
            ast?.findNodeAt(position.line, position.character)
        } ?: return null

        // Special-case imports:
        // Even when the position query returns a ClassNode (e.g. hovering on `List` in `import java.util.List`),
        // it is more intuitive to show the import statement hover rather than the referenced class.
        val importNodeAtLine = findImportNodeAtLine(documentUri, position.line)
        val importAwareNode = when {
            nodeAtPosition is ClassNode && importNodeAtLine != null -> importNodeAtLine
            else -> nodeAtPosition
        }

        logger.debug("Found node at position: ${importAwareNode.javaClass.simpleName}")

        // 2. If we have visitor/symbol table, try enhanced resolution
        if (astVisitor != null && symbolTable != null) {
            return resolveWithVisitor(importAwareNode, astVisitor, symbolTable)
        }

        // 3. Fallback resolution
        return resolveFallback(importAwareNode, documentUri)
    }

    private fun findImportNodeAtLine(documentUri: URI, lspLine: Int): ImportNode? {
        val module = compilationService.getAst(documentUri) as? ModuleNode ?: return null
        val groovyLine = lspLine + 1

        // NOTE: Heuristic / tradeoff:
        // We match ImportNode by its start line to detect "hover inside import". This is intentionally simple,
        // but could be wrong for unusual formatting (multi-line imports) or synthetic imports. If this becomes
        // a real-world issue, prefer a position-aware import range check (requires reliable end coordinates).
        val allImports = sequenceOf(
            module.imports?.asSequence() ?: emptySequence(),
            module.starImports?.asSequence() ?: emptySequence(),
            module.staticImports?.values?.asSequence() ?: emptySequence(),
            module.staticStarImports?.values?.asSequence() ?: emptySequence(),
        ).flatten()

        return allImports.firstOrNull { it.lineNumber == groovyLine }
    }

    private fun resolveWithVisitor(
        node: ASTNode,
        visitor: GroovyAstModel,
        symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
    ): ASTNode = when (node) {
        is ClassNode -> {
            // For hover, prefer the import statement container when hovering inside an import.
            // This provides a more intuitive hover (shows the full import text), while definition still
            // resolves the referenced class via definition/type definition.
            val parent = visitor.getParent(node)
            if (parent is ImportNode) parent else node
        }

        is VariableExpression -> resolveVariable(node, visitor, symbolTable)
        is ConstantExpression -> resolveConstant(node, visitor)
        else -> node
    }

    private fun resolveVariable(
        node: VariableExpression,
        visitor: GroovyAstModel,
        symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
    ): ASTNode {
        // If it's a declaration, return the DeclarationExpression for richer hover info
        val parent = visitor.getParent(node)
        if (parent is org.codehaus.groovy.ast.expr.DeclarationExpression && parent.leftExpression == node) {
            return parent // Return parent so TypeInferencer can infer the type
        }

        // Otherwise resolve to definition
        return node.resolveToDefinition(visitor, symbolTable, strict = false) ?: node
    }

    private fun resolveConstant(node: ConstantExpression, visitor: GroovyAstModel): ASTNode {
        val parent = visitor.getParent(node)
        if (parent is MethodCallExpression && parent.method == node) {
            return parent
        }
        return node
    }

    private fun resolveFallback(node: ASTNode, documentUri: URI): ASTNode {
        if (node is ConstantExpression) {
            val parent = compilationService.getAstModel(documentUri)?.getParent(node)
            if (parent is MethodCallExpression && parent.method == node) {
                return parent
            }
        }
        return node
    }

    /**
     * Extract the class name for documentation lookup from various node types.
     * This enables documentation fetching for both direct ClassNode references
     * and ImportNode wrappers (when hovering on an import statement).
     *
     * @param node The AST node to extract class name from
     * @return Fully qualified class name, or null if not applicable
     */
    private fun extractClassNameForDocumentation(node: ASTNode): String? = when (node) {
        is ClassNode -> node.name
        is ImportNode -> node.type?.name ?: node.className
        else -> null
    }

    /**
     * Create hover content using the DSL with documentation.
     */
    private suspend fun createHoverContent(node: ASTNode, documentUri: URI): Hover? {
        // Check if this is a Jenkins step and we have metadata for it
        val jenkinsHover = tryCreateJenkinsStepHover(node, documentUri)
        if (jenkinsHover != null) {
            return jenkinsHover
        }

        val baseHover = createHoverFor(node).getOrNull() ?: return null

        // Try to get documentation for the node
        var doc = try {
            documentationProvider.getDocumentation(node, documentUri)
        } catch (e: Exception) {
            logger.debug("Failed to get documentation for node", e)
            com.github.albertocavalcante.groovylsp.documentation.Documentation.EMPTY
        }

        // If standard doc is empty, try source navigation for binary classes
        // This handles both ClassNode and ImportNode (which wraps a class reference)
        if (doc.isEmpty() && sourceNavigator != null) {
            val className = extractClassNameForDocumentation(node)
            if (className != null) {
                val classpathUri = compilationService.findClasspathClass(className)
                if (classpathUri != null) {
                    try {
                        val result = sourceNavigator.navigateToSource(classpathUri, className)
                        if (result is SourceNavigator.SourceResult.SourceLocation && result.documentation != null) {
                            doc = result.documentation
                        }
                    } catch (e: Exception) {
                        logger.debug("Failed to get source documentation for $className", e)
                    }
                }
            }
        }

        if (doc.isEmpty()) {
            return baseHover
        }

        // Enhance hover with documentation
        return enhanceHoverWithDocumentation(baseHover, doc)
    }

    /**
     * Try to create a hover for a Jenkins step from bundled metadata.
     * Also checks for vars/ global variables with .txt documentation.
     */
    private fun tryCreateJenkinsStepHover(node: ASTNode, documentUri: URI): Hover? {
        // Only check for Jenkins files
        if (!compilationService.workspaceManager.isJenkinsFile(documentUri)) {
            return null
        }

        // Only for method calls
        if (node !is MethodCallExpression) {
            return null
        }

        val stepName = node.methodAsString ?: return null

        // First, check if this is a vars/ global variable call
        val varsHover = tryCreateVarsGlobalVariableHover(stepName, node)
        if (varsHover != null) {
            return varsHover
        }

        // Fall back to bundled Jenkins step metadata
        val metadata = compilationService.workspaceManager.getAllJenkinsMetadata() ?: return null
        val stepMetadata = JenkinsStepCompletionProvider.getStepMetadata(stepName, metadata) ?: return null

        // Build rich hover content for Jenkins step
        val markdownContent = markdown {
            h2("Jenkins Step: `$stepName`")

            stepMetadata.documentation?.let { doc ->
                text(doc)
            }

            stepMetadata.plugin?.let { plugin ->
                text("**Plugin:** $plugin")
            }

            // Use namedParams instead of parameters for MergedStepMetadata
            if (stepMetadata.namedParams.isNotEmpty()) {
                h3("Parameters")
                list(stepMetadata.namedParams.map { (name, param) ->
                    val required = if (param.required) " *(required)*" else ""
                    val defaultVal = param.defaultValue?.let { " (default: `$it`)" } ?: ""
                    val base = "**`$name`**: `${param.type}`$required$defaultVal"
                    param.description?.let { desc -> "$base\n  - $desc" } ?: base
                })
            }
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = markdownContent
        }

        // Build hover range from the method call expression
        val hoverRange = Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
        )

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = hoverRange
        }
    }

    /**
     * Try to create a hover for a vars/ global variable call.
     * Shows the documentation from the companion .txt file if available.
     */
    private fun tryCreateVarsGlobalVariableHover(varName: String, node: MethodCallExpression): Hover? {
        val globalVariables = compilationService.workspaceManager.getJenkinsGlobalVariables()
        val globalVar = globalVariables.find { it.name == varName } ?: return null

        // Build hover content
        val markdownContent = markdown {
            h2("Jenkins Shared Library: `$varName`")

            if (globalVar.documentation.isNotEmpty()) {
                text(globalVar.documentation)
            } else {
                text(italic("No documentation available. Add a `vars/$varName.txt` file to provide documentation."))
            }

            text("**Source:** `${globalVar.path.fileName}`")
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = markdownContent
        }

        val hoverRange = Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
        )

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = hoverRange
        }
    }

    /**
     * Enhance existing hover content with documentation.
     */
    private fun enhanceHoverWithDocumentation(
        baseHover: Hover,
        doc: com.github.albertocavalcante.groovylsp.documentation.Documentation,
    ): Hover {
        val existingContent = baseHover.contents.right?.value ?: return baseHover
        val docMarkdown = com.github.albertocavalcante.groovylsp.documentation.DocFormatter.formatAsMarkdown(doc)

        if (docMarkdown.isBlank()) {
            return baseHover
        }

        // Combine existing hover content with documentation
        val enhancedContent = buildString {
            append(existingContent)
            append("\n\n---\n\n")
            append(docMarkdown)
        }

        val markupContent = org.eclipse.lsp4j.MarkupContent().apply {
            kind = org.eclipse.lsp4j.MarkupKind.MARKDOWN
            value = enhancedContent
        }

        return Hover().apply {
            contents = org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(markupContent)
        }
    }
}
