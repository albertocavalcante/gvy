// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.groovyparser.ast.isHoverable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.documentation.DocExtractor
import com.github.albertocavalcante.gvy.gls.documentation.DocFormatter
import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import com.github.albertocavalcante.gvy.gls.documentation.DocumentationProvider
import com.github.albertocavalcante.gvy.gls.errors.GroovyLspException
import com.github.albertocavalcante.gvy.gls.errors.InvalidPositionException
import com.github.albertocavalcante.gvy.gls.errors.NodeNotFoundAtPositionException
import com.github.albertocavalcante.gvy.gls.errors.SymbolResolutionException
import com.github.albertocavalcante.gvy.gls.errors.invalidPosition
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ClassDeclarationHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ClosureHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ConstructorCallHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.DeclarationExpressionHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.FieldDeclarationHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.GenericHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ImportHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.JenkinsParameterHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.JenkinsStepHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.LiteralHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.MethodCallHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.MethodDeclarationHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.PropertyExpressionHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.VariableExpressionHoverStrategy
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.IOException
import java.net.URI

/**
 * Kotlin-idiomatic hover provider for Groovy symbols.
 * Uses coroutines, extension functions, and null safety for clean async processing.
 *
 * This provider uses a strategy pattern to handle different AST node types.
 * Strategies are evaluated in order, and the first one that can handle the node is used.
 */
class HoverProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val contentGenerator: HoverContentGenerator,
    private val sourceNavigator: SourceNavigator? = null,
) {
    private val logger = KotlinLogging.logger {}

    // Documentation provider for extracting groovydoc - use shared instance for cache consistency
    private val documentationProvider = DocumentationProvider
        .getInstance(documentProvider)

    // Strategies for handling different node types (order matters - more specific first)
    private val strategies: List<HoverStrategy>
        get() {
            val jenkinsCapabilities = compilationService.workspaceManager.getJenkinsCapabilities()
            val modeResolver = ModeResolver.fromConfig(compilationService.serverConfiguration, jenkinsCapabilities)
            return listOfNotNull(
                // Jenkins step hover has high priority for method calls in Jenkins files
                jenkinsCapabilities?.let { JenkinsStepHoverStrategy(it, modeResolver) },
                jenkinsCapabilities?.let { JenkinsParameterHoverStrategy(it, modeResolver) },
                MethodDeclarationHoverStrategy(),
                ClassDeclarationHoverStrategy(),
                FieldDeclarationHoverStrategy(),
                ImportHoverStrategy(),
                DeclarationExpressionHoverStrategy(),
                VariableExpressionHoverStrategy(),
                MethodCallHoverStrategy(),
                ConstructorCallHoverStrategy(), // Phase 4: Constructor calls
                PropertyExpressionHoverStrategy(), // Phase 4: Property access (object.property)
                ClosureHoverStrategy(), // Phase 4: Closure expressions
                LiteralHoverStrategy(), // Phase 4: Literals (strings, numbers, lists, maps)
                GenericHoverStrategy(), // Fallback - must be last
            )
        }

    /**
     * Provide hover information for the symbol at the given position.
     * Returns null if no hover information is available.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    suspend fun provideHover(uri: String, position: Position): Hover? = provideHoverInternal(uri, position, null)

    internal suspend fun provideHover(uri: String, position: Position, snapshot: CompiledDocumentSnapshot): Hover? =
        provideHoverInternal(uri, position, snapshot)

    private suspend fun provideHoverInternal(
        uri: String,
        position: Position,
        snapshot: CompiledDocumentSnapshot?,
    ): Hover? = withContext(Dispatchers.Default) {
        try {
            hoverForSource(uri, position, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeNotFoundAtPositionException) {
            logger.debug { "No node found at position for hover: $e" }
            null
        } catch (e: InvalidPositionException) {
            logger.warn { "Invalid position for hover: $e" }
            null
        } catch (e: SymbolResolutionException) {
            logger.debug { "Symbol resolution failed for hover: $e" }
            null
        } catch (e: IllegalStateException) {
            logger.error(e) { "Error providing hover for $uri at ${position.line}:${position.character}" }
            null
        } catch (e: IllegalArgumentException) {
            val documentUri = URI.create(uri)
            val specificException = documentUri.invalidPosition(
                position.line,
                position.character,
                e.message ?: "Invalid arguments",
            )
            logger.error(e) { "Invalid arguments for hover: $specificException" }
            null
        } catch (e: GroovyLspException) {
            logger.error(e) { "LSP error providing hover for $uri at ${position.line}:${position.character}" }
            null
        } catch (e: IOException) {
            logger.error(e) { "I/O error providing hover for $uri at ${position.line}:${position.character}" }
            null
        } catch (e: Exception) {
            rethrowIfCancellationOrError(e)
            logger.error(e) { "Unexpected error providing hover for $uri at ${position.line}:${position.character}" }
            null
        }
    }

    private suspend fun hoverForSource(uri: String, position: Position, snapshot: CompiledDocumentSnapshot?): Hover? {
        val documentUri = URI.create(uri)
        if (snapshot == null) ensureAstPrepared(documentUri)
        val compiled = snapshot ?: compilationService.getCompiledSnapshot(documentUri)
        val source = compiled?.sourceText ?: documentProvider.get(documentUri) ?: return null
        val index = TextIndex(source)
        val parseResult = compiled?.parseResult ?: compilationService.getParseResult(documentUri)
        if (!isHoverPosition(index, position, parseResult?.tokenIndex)) return null
        val groovyPosition = checkNotNull(index.codePointPosition(position)).toGroovyPosition()
        val selected = resolveHoverNode(documentUri, groovyPosition, compiled)
        val node = selected?.let { parameterTypeHoverNode(it, index, position) }
        return if (node == null || !node.isHoverable()) {
            null
        } else {
            createHoverContent(node, documentUri, compiled)?.apply {
                range = range?.let { index.utf16Range(it) } ?: hoverIdentifierRange(index, position)
            }
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
            rethrowIfCancellationOrError(error)
            logger.debug(error) { "HoverProvider: failed to compile $documentUri before hover" }
        }
    }

    /**
     * Resolve the appropriate node for hover display.
     */
    private fun resolveHoverNode(
        documentUri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
        snapshot: CompiledDocumentSnapshot?,
    ): ASTNode? {
        val astVisitor = snapshot?.parseResult?.astModel ?: compilationService.getAstModel(documentUri)
        val symbolTable = snapshot?.parseResult?.symbolTable ?: compilationService.getSymbolTable(documentUri)

        // 1. Find the node at position
        val nodeAtPosition = if (astVisitor != null) {
            astVisitor.getNodeAt(documentUri, position)
        } else {
            val ast = module(documentUri, snapshot)
            ast?.findNodeAt(position.line, position.character)
        } ?: return null

        // Special-case imports:
        // Even when the position query returns a ClassNode (e.g. hovering on `List` in `import java.util.List`),
        // it is more intuitive to show the import statement hover rather than the referenced class.
        val importNodeAtLine = findImportNodeAtLine(module(documentUri, snapshot), position.line)
        val importAwareNode = when {
            nodeAtPosition is ClassNode && importNodeAtLine != null -> importNodeAtLine
            else -> nodeAtPosition
        }

        logger.debug { "Found node at position: ${importAwareNode.javaClass.simpleName}" }

        // 2. If we have visitor/symbol table, try enhanced resolution
        if (astVisitor != null && symbolTable != null) {
            return resolveWithVisitor(importAwareNode, astVisitor, symbolTable)
        }

        // 3. Fallback resolution
        return resolveFallback(importAwareNode, astVisitor)
    }

    private fun module(documentUri: URI, snapshot: CompiledDocumentSnapshot?): ModuleNode? =
        if (snapshot != null) snapshot.parseResult.ast else compilationService.getAst(documentUri) as? ModuleNode

    private fun findImportNodeAtLine(module: ModuleNode?, lspLine: Int): ImportNode? {
        module ?: return null
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
        if (parent is DeclarationExpression && parent.leftExpression == node) {
            return parent // Return parent so SemanticTypeResolver can resolve the type
        }

        // The implicit `this` receiver of `foo(...)` shares the method name's position; hovering the name
        // means the call, not the script class `this` resolves to.
        if (parent is MethodCallExpression && parent.isImplicitThis && parent.objectExpression === node) {
            return parent
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

    private fun resolveFallback(node: ASTNode, visitor: GroovyAstModel?): ASTNode {
        if (node is ConstantExpression) {
            val parent = visitor?.getParent(node)
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
        is ClassExpression -> node.type.name
        is ImportNode -> {
            val type: ClassNode? = node.type
            type?.name ?: node.className
        }

        else -> null
    }

    /**
     * Create hover content using the strategy pattern with documentation enhancement.
     */
    private suspend fun createHoverContent(
        node: ASTNode,
        documentUri: URI,
        snapshot: CompiledDocumentSnapshot?,
    ): Hover? {
        val module = module(documentUri, snapshot)
        val context = HoverContext(
            moduleNode = module,
            contentGenerator = contentGenerator,
            documentUri = documentUri,
            astModel = snapshot?.parseResult?.astModel ?: compilationService.getAstModel(documentUri),
        )

        // Use strategy pattern to generate base hover
        // Iterate through all matching strategies until one returns a non-null hover
        // This allows fallback when a strategy returns null (e.g., JenkinsStepHoverStrategy
        // returns null for non-Jenkins files, falling back to MethodCallHoverStrategy)
        val baseHover = strategies
            .asSequence()
            .filter { it.canHandle(node) }
            .mapNotNull { it.generateHover(node, context) }
            .firstOrNull()
            ?: return null

        logger.debug { "Generated base hover for ${node.javaClass.simpleName}:\n${baseHover.contents.right?.value}" }

        // Try to get documentation for the node
        var doc = try {
            documentationFor(node, documentUri, snapshot)
        } catch (e: Exception) {
            rethrowIfCancellationOrError(e)
            logger.debug(e) { "Failed to get documentation for node" }
            Documentation.EMPTY
        }

        // If standard doc is empty, try source navigation for binary classes
        // This handles both ClassNode and ImportNode (which wraps a class reference)
        if (doc.isEmpty()) doc = sourceDocumentation(node) ?: doc

        if (doc.isEmpty()) {
            return baseHover
        }

        // Enhance hover with documentation
        return enhanceHoverWithDocumentation(baseHover, doc)
    }

    private fun documentationFor(node: ASTNode, uri: URI, snapshot: CompiledDocumentSnapshot?): Documentation {
        val classDeclaration = when (node) {
            is ClassNode -> node.redirect()
            is ClassExpression -> node.type.redirect()
            is ImportNode -> node.type?.redirect()
            else -> null
        }
        if (classDeclaration != null) {
            val attached = DocExtractor.extractAttachedDocumentation(classDeclaration)
            if (!attached.isEmpty()) return attached
            // A referenced class's positions index into its own source, never the caller's buffer.
            if (classDeclaration.module !== module(uri, snapshot)) return Documentation.EMPTY
        }
        val declaration = classDeclaration ?: node
        return if (snapshot != null) {
            DocExtractor.extractDocumentation(snapshot.sourceText, declaration)
        } else {
            documentationProvider.getDocumentation(declaration, uri)
        }
    }

    private suspend fun sourceDocumentation(node: ASTNode): Documentation? {
        val navigator = sourceNavigator ?: return null
        val className = extractClassNameForDocumentation(node) ?: return null
        val classpathUri = compilationService.findClasspathClass(className) ?: return null
        return try {
            (navigator.navigateToSource(classpathUri, className) as? SourceNavigator.SourceResult.SourceLocation)
                ?.documentation
        } catch (e: Exception) {
            rethrowIfCancellationOrError(e)
            logger.debug(e) { "Failed to get source documentation for $className" }
            null
        }
    }

    /**
     * Enhance existing hover content with documentation.
     */
    private fun enhanceHoverWithDocumentation(baseHover: Hover, doc: Documentation): Hover {
        val existingContent = baseHover.contents.right?.value ?: return baseHover
        val docMarkdown = DocFormatter.formatAsMarkdown(doc)

        if (docMarkdown.isBlank()) {
            return baseHover
        }

        // Combine existing hover content with documentation
        val enhancedContent = buildString {
            append(existingContent)
            append("\n\n---\n\n")
            append(docMarkdown)
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = enhancedContent
        }

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = baseHover.range
        }
    }
}
