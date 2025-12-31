package com.github.albertocavalcante.groovylsp.engine.adapters

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Unified parse result representation that all parser adapters produce.
 *
 * This is the abstraction layer that decouples the LSP from specific parser implementations.
 * Each parser (groovy-parser, groovyparser-core, OpenRewrite, etc.) is wrapped by an adapter
 * that translates its output into this common vocabulary.
 *
 * Design principle: Adapters, not interfaces - parsers don't implement this interface directly.
 * Instead, adapters wrap parser output and translate to these types.
 */
interface ParseUnit {
    val uri: String

    /** True if parsing completed without errors */
    val isSuccessful: Boolean

    /** LSP diagnostics translated from parser errors/warnings */
    val diagnostics: List<Diagnostic>

    /**
     * Find the node at the given position (for hover, go-to-definition, etc.)
     *
     * @param position 0-based LSP position
     * @return The node at position, or null if not found
     */
    fun nodeAt(position: Position): UnifiedNode?

    /**
     * Get all symbols in this unit (for document symbols, outline).
     *
     * @return List of symbols in document order
     */
    fun allSymbols(): List<UnifiedSymbol>
}

/**
 * Minimal node representation for cross-parser operations.
 *
 * Contains only the information needed by LSP features, independent of
 * the underlying AST representation.
 */
data class UnifiedNode(
    /** Node name (class name, method name, variable name, etc.) */
    val name: String?,
    /** Kind of node for categorization */
    val kind: UnifiedNodeKind,
    /** Type of this node (for variables, fields, method return types) */
    val type: String?,
    /** Documentation (Javadoc, line comment, etc.) */
    val documentation: String?,
    /** Source range in the document */
    val range: Range?,
    /**
     * Opaque reference to the original AST node.
     * Engine-specific code can cast this to the expected type.
     */
    val originalNode: Any? = null,
)

/**
 * Node kind classification for LSP symbol categorization.
 */
enum class UnifiedNodeKind {
    CLASS,
    INTERFACE,
    TRAIT,
    ENUM,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    PROPERTY,
    VARIABLE,
    PARAMETER,
    CLOSURE,
    IMPORT,
    PACKAGE,
    SCRIPT,
    OTHER,
}

/**
 * Symbol representation for document outline and workspace symbols.
 */
data class UnifiedSymbol(
    val name: String,
    val kind: UnifiedNodeKind,
    val range: Range,
    val selectionRange: Range,
    val children: List<UnifiedSymbol> = emptyList(),
)
