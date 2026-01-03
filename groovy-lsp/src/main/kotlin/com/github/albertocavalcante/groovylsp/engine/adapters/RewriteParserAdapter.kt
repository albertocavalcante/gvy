package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import com.github.albertocavalcante.groovyparser.api.ParseUnit as ApiParseUnit
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
            Range(
                Position(apiDiag.range.start.line - 1, apiDiag.range.start.column - 1),
                Position(apiDiag.range.end.line - 1, apiDiag.range.end.column - 1),
            ),
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
            range = Range(
                Position(nodeInfo.range.start.line - 1, nodeInfo.range.start.column - 1),
                Position(nodeInfo.range.end.line - 1, nodeInfo.range.end.column - 1),
            ),
        )
    }

    override fun allSymbols(): List<UnifiedSymbol> = parseUnit.symbols().map { symbol ->
        UnifiedSymbol(
            name = symbol.name,
            kind = symbol.kind.toUnifiedKind(),
            range = Range(
                Position(symbol.range.start.line - 1, symbol.range.start.column - 1),
                Position(symbol.range.end.line - 1, symbol.range.end.column - 1),
            ),
            selectionRange = Range(
                Position(symbol.range.start.line - 1, symbol.range.start.column - 1),
                Position(symbol.range.end.line - 1, symbol.range.end.column - 1),
            ),
        )
    }
}

/**
 * Extension to convert API NodeKind to unified kind.
 */
private fun NodeKind.toUnifiedKind(): UnifiedNodeKind = when (this) {
    // Declarations
    NodeKind.CLASS -> UnifiedNodeKind.CLASS
    NodeKind.INTERFACE -> UnifiedNodeKind.INTERFACE
    NodeKind.ENUM -> UnifiedNodeKind.ENUM
    NodeKind.TRAIT -> UnifiedNodeKind.TRAIT
    NodeKind.METHOD -> UnifiedNodeKind.METHOD
    NodeKind.CONSTRUCTOR -> UnifiedNodeKind.CONSTRUCTOR
    NodeKind.FIELD -> UnifiedNodeKind.FIELD
    NodeKind.PROPERTY -> UnifiedNodeKind.PROPERTY
    NodeKind.PARAMETER -> UnifiedNodeKind.PARAMETER
    NodeKind.VARIABLE -> UnifiedNodeKind.VARIABLE

    // Expressions
    NodeKind.METHOD_CALL -> UnifiedNodeKind.OTHER
    NodeKind.PROPERTY_ACCESS -> UnifiedNodeKind.OTHER
    NodeKind.VARIABLE_REFERENCE -> UnifiedNodeKind.VARIABLE
    NodeKind.LITERAL -> UnifiedNodeKind.OTHER
    NodeKind.BINARY_EXPRESSION -> UnifiedNodeKind.OTHER
    NodeKind.UNARY_EXPRESSION -> UnifiedNodeKind.OTHER
    NodeKind.CLOSURE -> UnifiedNodeKind.CLOSURE
    NodeKind.LIST -> UnifiedNodeKind.OTHER
    NodeKind.MAP -> UnifiedNodeKind.OTHER
    NodeKind.RANGE -> UnifiedNodeKind.OTHER

    // Statements
    NodeKind.IF -> UnifiedNodeKind.OTHER
    NodeKind.FOR -> UnifiedNodeKind.OTHER
    NodeKind.WHILE -> UnifiedNodeKind.OTHER
    NodeKind.SWITCH -> UnifiedNodeKind.OTHER
    NodeKind.TRY -> UnifiedNodeKind.OTHER
    NodeKind.RETURN -> UnifiedNodeKind.OTHER
    NodeKind.THROW -> UnifiedNodeKind.OTHER
    NodeKind.ASSERT -> UnifiedNodeKind.OTHER
    NodeKind.BLOCK -> UnifiedNodeKind.OTHER

    // Other
    NodeKind.IMPORT -> UnifiedNodeKind.IMPORT
    NodeKind.PACKAGE -> UnifiedNodeKind.PACKAGE
    NodeKind.ANNOTATION -> UnifiedNodeKind.OTHER
    NodeKind.COMMENT -> UnifiedNodeKind.OTHER
    NodeKind.UNKNOWN -> UnifiedNodeKind.OTHER
}

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
