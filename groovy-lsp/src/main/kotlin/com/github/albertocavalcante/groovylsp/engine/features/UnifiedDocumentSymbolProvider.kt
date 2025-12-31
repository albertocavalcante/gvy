package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedSymbol
import com.github.albertocavalcante.groovylsp.engine.api.DocumentSymbolProvider
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind

/**
 * Unified implementation of Document Symbols (Outline).
 *
 * Translates the parser-agnostic [UnifiedSymbol]s provided by [ParseUnit]
 * into LSP [DocumentSymbol]s.
 */
class UnifiedDocumentSymbolProvider(private val parseUnit: ParseUnit) : DocumentSymbolProvider {

    override fun getDocumentSymbols(): List<DocumentSymbol> = parseUnit.allSymbols().map { it.toLspDocumentSymbol() }

    private fun UnifiedSymbol.toLspDocumentSymbol(): DocumentSymbol {
        val symbol = DocumentSymbol(
            this.name,
            this.kind.toSymbolKind(),
            this.range,
            this.selectionRange,
            null, // detail not yet supported in UnifiedSymbol
        )

        // Recursive mapping of children
        if (this.children.isNotEmpty()) {
            symbol.children = this.children.map { it.toLspDocumentSymbol() }
        }

        return symbol
    }

    private fun UnifiedNodeKind.toSymbolKind(): SymbolKind = when (this) {
        UnifiedNodeKind.CLASS -> SymbolKind.Class
        UnifiedNodeKind.INTERFACE -> SymbolKind.Interface
        UnifiedNodeKind.TRAIT -> SymbolKind.Interface // Map trait to Interface
        UnifiedNodeKind.ENUM -> SymbolKind.Enum
        UnifiedNodeKind.METHOD -> SymbolKind.Method
        UnifiedNodeKind.CONSTRUCTOR -> SymbolKind.Constructor
        UnifiedNodeKind.FIELD -> SymbolKind.Field
        UnifiedNodeKind.PROPERTY -> SymbolKind.Property
        UnifiedNodeKind.VARIABLE -> SymbolKind.Variable
        UnifiedNodeKind.PARAMETER -> SymbolKind.Variable
        UnifiedNodeKind.CLOSURE -> SymbolKind.Function
        UnifiedNodeKind.IMPORT -> SymbolKind.Module
        UnifiedNodeKind.PACKAGE -> SymbolKind.Package
        UnifiedNodeKind.SCRIPT -> SymbolKind.File
        UnifiedNodeKind.OTHER -> SymbolKind.Variable // Fallback
    }
}
