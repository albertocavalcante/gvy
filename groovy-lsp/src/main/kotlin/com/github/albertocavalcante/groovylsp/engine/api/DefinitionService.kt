package com.github.albertocavalcante.groovylsp.engine.api

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import org.eclipse.lsp4j.Range

/**
 * Service for cross-file definition lookup.
 * Abstracted to support both Native (Lucene-based) and Core (SymbolSolver-based) strategies.
 */
interface DefinitionService {

    /**
     * Find definition(s) for the given node.
     *
     * @param node The symbol/node to look up (from ParseUnit), if found
     * @param context The ParseUnit context (containing URI, etc.)
     * @param position The original LSP position of the request
     * @return List of definitions
     */
    suspend fun findDefinition(
        node: UnifiedNode?,
        context: ParseUnit,
        position: org.eclipse.lsp4j.Position,
    ): List<UnifiedDefinition>
}

/**
 * Platform-agnostic definition result.
 */
data class UnifiedDefinition(
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val kind: DefinitionKind,
    val originSelectionRange: Range? = null,
)

enum class DefinitionKind {
    SOURCE, // Found in source code
    BINARY, // Found in compiled class/library
}
