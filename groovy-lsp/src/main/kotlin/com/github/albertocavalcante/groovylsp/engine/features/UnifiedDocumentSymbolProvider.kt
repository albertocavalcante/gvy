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

    private fun UnifiedNodeKind.toSymbolKind(): SymbolKind = unifiedNodeKindToSymbolKind[this] ?: SymbolKind.Variable

    private companion object {
        private val unifiedNodeKindToSymbolKind: Map<UnifiedNodeKind, SymbolKind> = mapOf(
            UnifiedNodeKind.CLASS to SymbolKind.Class,
            UnifiedNodeKind.INTERFACE to SymbolKind.Interface,
            UnifiedNodeKind.TRAIT to SymbolKind.Interface,
            UnifiedNodeKind.ENUM to SymbolKind.Enum,
            UnifiedNodeKind.METHOD to SymbolKind.Method,
            UnifiedNodeKind.CONSTRUCTOR to SymbolKind.Constructor,
            UnifiedNodeKind.FIELD to SymbolKind.Field,
            UnifiedNodeKind.PROPERTY to SymbolKind.Property,
            UnifiedNodeKind.VARIABLE to SymbolKind.Variable,
            UnifiedNodeKind.PARAMETER to SymbolKind.Variable,
            UnifiedNodeKind.CLOSURE to SymbolKind.Function,
            UnifiedNodeKind.IMPORT to SymbolKind.Module,
            UnifiedNodeKind.PACKAGE to SymbolKind.Package,
            UnifiedNodeKind.SCRIPT to SymbolKind.File,
            UnifiedNodeKind.OTHER to SymbolKind.Variable,
        )
    }
}
