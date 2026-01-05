package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import com.github.albertocavalcante.groovyparser.api.ParseUnit as ApiParseUnit
import com.github.albertocavalcante.groovyparser.api.model.Range as ApiRange
import com.github.albertocavalcante.groovyparser.api.model.Severity as ApiSeverity

/**
 * Adapter that wraps parser:rewrite's [ApiParseUnit] into the LSP [ParseUnit] interface.
 *
 * This adapter translates OpenRewrite's lossless LST into the parser-agnostic
 * types that LSP features consume.
 */
class RewriteParserAdapter(private val parseUnit: ApiParseUnit, override val uri: String) : ParseUnit {

    override val isSuccessful: Boolean = parseUnit.isSuccessful

    override val diagnostics: List<Diagnostic> = parseUnit.diagnostics().map { apiDiag ->
        Diagnostic(
            apiDiag.range.toLspRange(),
            apiDiag.message,
            when (apiDiag.severity) {
                ApiSeverity.ERROR -> DiagnosticSeverity.Error
                ApiSeverity.WARNING -> DiagnosticSeverity.Warning
                ApiSeverity.INFO -> DiagnosticSeverity.Information
                ApiSeverity.HINT -> DiagnosticSeverity.Hint
            },
            apiDiag.source,
        )
    }

    override fun nodeAt(position: Position): UnifiedNode? {
        // Convert LSP 0-based to API 1-based
        val apiPosition = com.github.albertocavalcante.groovyparser.api.model.Position(
            position.line + 1,
            position.character + 1,
        )
        val nodeInfo = parseUnit.nodeAt(apiPosition) ?: return null

        return UnifiedNode(
            name = nodeInfo.name,
            kind = nodeInfo.kind.toUnifiedKind(),
            type = null, // OpenRewrite doesn't expose type info in initial implementation
            documentation = null, // Could extract from Space/comments in future
            range = nodeInfo.range.toLspRange(),
        )
    }

    override fun allSymbols(): List<UnifiedSymbol> = parseUnit.symbols().map { symbol ->
        UnifiedSymbol(
            name = symbol.name,
            kind = symbol.kind.toUnifiedKind(),
            range = symbol.range.toLspRange(),
            selectionRange = symbol.range.toLspRange(),
        )
    }
}

/**
 * Extension to convert API NodeKind to unified kind.
 */
private val nodeKindToUnifiedKind: Map<NodeKind, UnifiedNodeKind> = mapOf(
    // Declarations
    NodeKind.CLASS to UnifiedNodeKind.CLASS,
    NodeKind.INTERFACE to UnifiedNodeKind.INTERFACE,
    NodeKind.ENUM to UnifiedNodeKind.ENUM,
    NodeKind.TRAIT to UnifiedNodeKind.TRAIT,
    NodeKind.METHOD to UnifiedNodeKind.METHOD,
    NodeKind.CONSTRUCTOR to UnifiedNodeKind.CONSTRUCTOR,
    NodeKind.FIELD to UnifiedNodeKind.FIELD,
    NodeKind.PROPERTY to UnifiedNodeKind.PROPERTY,
    NodeKind.PARAMETER to UnifiedNodeKind.PARAMETER,
    NodeKind.VARIABLE to UnifiedNodeKind.VARIABLE,

    // Expressions
    NodeKind.VARIABLE_REFERENCE to UnifiedNodeKind.VARIABLE,
    NodeKind.CLOSURE to UnifiedNodeKind.CLOSURE,

    // Other
    NodeKind.IMPORT to UnifiedNodeKind.IMPORT,
    NodeKind.PACKAGE to UnifiedNodeKind.PACKAGE,
)

private fun NodeKind.toUnifiedKind(): UnifiedNodeKind = nodeKindToUnifiedKind[this] ?: UnifiedNodeKind.OTHER

/**
 * Extension to convert API SymbolKind to unified kind.
 */
private fun SymbolKind.toUnifiedKind(): UnifiedNodeKind = when (this) {
    SymbolKind.CLASS -> UnifiedNodeKind.CLASS
    SymbolKind.INTERFACE -> UnifiedNodeKind.INTERFACE
    SymbolKind.ENUM -> UnifiedNodeKind.ENUM
    SymbolKind.METHOD, SymbolKind.FUNCTION -> UnifiedNodeKind.METHOD
    SymbolKind.CONSTRUCTOR -> UnifiedNodeKind.CONSTRUCTOR
    SymbolKind.FIELD -> UnifiedNodeKind.FIELD
    SymbolKind.PROPERTY -> UnifiedNodeKind.PROPERTY
    SymbolKind.VARIABLE -> UnifiedNodeKind.VARIABLE
    SymbolKind.PACKAGE, SymbolKind.MODULE, SymbolKind.NAMESPACE -> UnifiedNodeKind.PACKAGE
    SymbolKind.CONSTANT -> UnifiedNodeKind.FIELD
    else -> UnifiedNodeKind.OTHER
}

private fun ApiRange.toLspRange(): Range = Range(
    Position((start.line - 1).coerceAtLeast(0), (start.column - 1).coerceAtLeast(0)),
    Position((end.line - 1).coerceAtLeast(0), (end.column - 1).coerceAtLeast(0)),
)
