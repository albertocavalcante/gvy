package com.github.albertocavalcante.groovylsp.engine.impl.native.features

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.CompletionService
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory

class NativeCompletionService(private val compilationService: GroovyCompilationService) : CompletionService {

    override suspend fun getCompletions(
        params: CompletionParams,
        context: ParseUnit?, // Unused: legacy provider manages its own context
        content: String,
    ): Either<List<CompletionItem>, CompletionList> {
        val uri = params.textDocument.uri
        val position = params.position

        return try {
            // Delegate to legacy provider
            val items = CompletionProvider.getContextualCompletions(
                uri = uri,
                line = position.line,
                character = position.character,
                compilationService = compilationService,
                content = content,
            )
            Either.forLeft(items)
        } catch (e: Exception) {
            // Log error and return empty list to prevent LSP disruption
            logger.error(
                "Error getting completions for URI: {} at position: {}:{}",
                uri,
                position.line,
                position.character,
                e,
            )
            Either.forLeft(emptyList())
        }
    }
}

private val logger = LoggerFactory.getLogger(NativeCompletionService::class.java)
