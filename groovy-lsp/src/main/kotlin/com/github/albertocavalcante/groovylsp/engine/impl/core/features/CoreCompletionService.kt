package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.api.CompletionService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Completion service for the Core (JavaParser-style) parser engine.
 *
 * Provides basic keyword completions and will be enhanced with
 * semantic completions using GroovySymbolResolver.
 */
class CoreCompletionService : CompletionService {

    override suspend fun getCompletions(
        params: CompletionParams,
        context: ParseUnit?,
        content: String,
    ): Either<List<CompletionItem>, CompletionList> {
        val items = mutableListOf<CompletionItem>()

        // Add Groovy keywords (using mapTo for efficiency)
        GROOVY_KEYWORDS.mapTo(items) { keyword ->
            CompletionItem(keyword).apply {
                kind = CompletionItemKind.Keyword
                detail = "Keyword"
            }
        }

        // Add basic types
        BASIC_TYPES.mapTo(items) { type ->
            CompletionItem(type).apply {
                kind = CompletionItemKind.Class
                detail = "Type"
            }
        }

        // Add contextual completions from ParseUnit
        context?.allSymbols()?.forEach { symbol ->
            val kind = mapSymbolKind(symbol.kind)
            items.add(
                CompletionItem(symbol.name).apply {
                    this.kind = kind
                    this.detail = symbol.kind.name.lowercase().replaceFirstChar { it.uppercase() }
                },
            )
        }

        return Either.forLeft(items.distinctBy { it.label to it.kind })
    }

    private fun mapSymbolKind(kind: UnifiedNodeKind): CompletionItemKind = when (kind) {
        UnifiedNodeKind.CLASS -> CompletionItemKind.Class
        UnifiedNodeKind.INTERFACE -> CompletionItemKind.Interface
        UnifiedNodeKind.TRAIT -> CompletionItemKind.Interface
        UnifiedNodeKind.ENUM -> CompletionItemKind.Enum
        UnifiedNodeKind.METHOD -> CompletionItemKind.Method
        UnifiedNodeKind.CONSTRUCTOR -> CompletionItemKind.Constructor
        UnifiedNodeKind.FIELD -> CompletionItemKind.Field
        UnifiedNodeKind.PROPERTY -> CompletionItemKind.Property
        UnifiedNodeKind.VARIABLE -> CompletionItemKind.Variable
        UnifiedNodeKind.PARAMETER -> CompletionItemKind.Variable
        UnifiedNodeKind.CLOSURE -> CompletionItemKind.Function
        UnifiedNodeKind.IMPORT -> CompletionItemKind.Module
        UnifiedNodeKind.PACKAGE -> CompletionItemKind.Module
        else -> CompletionItemKind.Text
    }

    companion object {
        private val GROOVY_KEYWORDS = listOf(
            "def", "var", "void", "class", "interface", "trait", "enum",
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "try", "catch", "finally", "throw", "throws",
            "new", "this", "super", "null", "true", "false", "assert",
            "public", "private", "protected", "static", "final", "abstract",
            "import", "package", "as", "in", "instanceof",
            "synchronized", "volatile", "transient", "extends", "implements",
        )

        private val BASIC_TYPES = listOf(
            "String", "int", "Integer", "long", "Long", "double", "Double",
            "boolean", "Boolean", "float", "Float", "byte", "Byte",
            "short", "Short", "char", "Character",
            "List", "Map", "Set", "Object",
            "BigDecimal", "BigInteger",
        )
    }
}
