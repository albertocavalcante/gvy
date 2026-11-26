// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.gvy.gls.converters.toProtocolRange
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionInputs
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.semantics.db.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/**
 * Resolves symbols using SemanticDB and WorkspaceSymbolIndex for full cross-file resolution.
 *
 * This strategy handles cross-file resolution for all symbol types:
 * - Methods (cross-file method calls)
 * - Fields (cross-file field access)
 * - Properties (cross-file property access)
 * - Constructors (cross-file constructor calls)
 * - Inherited members (methods/fields from parent classes)
 *
 * **Priority: MEDIUM** - runs after LocalSymbol (same-file) but before GlobalClass (class-only).
 *
 * Uses the SemanticDB to find occurrences at the cursor position, then resolves them
 * to definitions using the WorkspaceSymbolIndex.
 *
 * @property workspaceSymbolIndex Index for workspace-wide symbol lookup (provides access to SemanticDB)
 */
class SemanticDBResolutionStrategy(private val workspaceSymbolIndex: WorkspaceSymbolIndex) : SymbolResolutionStrategy {

    private val logger = KotlinLogging.logger {}

    @Suppress("ReturnCount") // Multiple validation checks require early returns
    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        logger.debug { "Attempting SemanticDB resolution at ${context.documentUri}:${context.position}" }

        if (context.targetNode is ImportNode) {
            logger.debug { "Skipping SemanticDB resolution for ImportNode" }
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        // 1. Get SemanticDocument for current file
        val inputs = context.inputs
        val selected = boundType(context.targetNode)
        if (inputs != null && selected?.isResolved == true && !selected.isPrimaryClassNode) {
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }
        val graph = inputs?.index()
        val document = if (graph != null && inputs != null) {
            graph.getDocumentSnapshot(context.documentUri)?.let { indexed ->
                inputs.qualify(context.documentUri, indexed)
                indexed.document
            }
        } else {
            workspaceSymbolIndex.getDocument(context.documentUri)
        }
        if (document == null) {
            logger.debug { "No SemanticDocument found for ${context.documentUri}" }
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        // 2. Find closest/smallest SymbolOccurrence at cursor position
        // Multiple occurrences may overlap (e.g. Class contains Method contains Expression)
        val line = context.position.line
        val char = context.position.character

        val occurrence = document.occurrences
            .filter { it.range.contains(line, char) && it.range.startLine == it.range.endLine }
            .minByOrNull { it.range.endColumn - it.range.startColumn }
        if (occurrence == null) {
            logger.debug {
                "No occurrence found at ${context.documentUri}:${context.position.line}:${context.position.character}"
            }
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        logger.debug { "Found occurrence for symbol: ${occurrence.symbol} at cursor position" }

        if (graph != null && inputs != null) {
            return resolveCaptured(graph, inputs, context.documentUri, selected, occurrence.symbol)
        }
        return resolveIndexed(occurrence.symbol)
    }

    private suspend fun resolveCaptured(
        graph: CapturedWorkspaceReferences,
        inputs: DefinitionInputs,
        origin: URI,
        selected: ClassNode?,
        symbol: String,
    ): ResolutionResult {
        val (uri, info) = graph.uniqueDefinition(symbol)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        val indexed = checkNotNull(graph.getDocumentSnapshot(uri))
        inputs.qualify(uri, indexed)
        if (uri != origin && selected?.let(inputs::declarationUri) != uri) {
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }
        val raw = info.range
        val range = Range(Position(raw.startLine, raw.startColumn), Position(raw.endLine, raw.endColumn))
        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Binary(uri, info.name, range.toProtocolRange(indexed.sourceText)),
        )
    }

    private fun resolveIndexed(symbol: String): ResolutionResult {
        // 3. Use WorkspaceSymbolIndex to find definition
        val location = workspaceSymbolIndex.findDefinition(symbol)
        if (location == null) {
            logger.debug { "Symbol $symbol not found in workspace index" }
            return SymbolResolutionStrategy.notFound("Symbol not found in workspace index", STRATEGY_NAME)
        }

        logger.debug { "Resolved symbol $symbol to ${location.uri}" }

        // 4. Get symbol info to extract the name
        val symbolInfo = workspaceSymbolIndex.findSymbol(symbol)
        val symbolName = symbolInfo?.name ?: Symbol(symbol).member

        // 5. Return DefinitionResult.Binary (we don't have access to AST nodes in this strategy)
        // The LSP will handle loading the source when the user navigates to the definition
        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Binary(
                uri = URI(location.uri),
                name = symbolName,
                range = location.range,
            ),
        )
    }

    companion object {
        private const val STRATEGY_NAME = "SemanticDB"
    }
}
