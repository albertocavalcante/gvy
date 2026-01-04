package com.github.albertocavalcante.groovylsp.engine.impl.native.features

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.CompletionService
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import kotlinx.coroutines.CancellationException
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

        val items =
            runCatching {
                CompletionProvider.getContextualCompletions(
                    uri = uri,
                    line = position.line,
                    character = position.character,
                    compilationService = compilationService,
                    content = content,
                )
            }
                .onFailure { throwable ->
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is Error -> throw throwable
                        else ->
                            logger.error(
                                "Error getting completions for URI: {} at position: {}:{}",
                                uri,
                                position.line,
                                position.character,
                                throwable,
                            )
                    }
                }
                .getOrDefault(emptyList())

        return Either.forLeft(items)
    }
}

private val logger = LoggerFactory.getLogger(NativeCompletionService::class.java)
