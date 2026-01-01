package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
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

        // TODO(#530): Add contextual completions using GroovySymbolResolver
        //   - Get symbols in scope from context?.allSymbols()
        //   - Use TypeSolver to resolve types for method completion
        //   - Integrate with legacy CompletionProvider for full functionality

        return Either.forLeft(items)
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
