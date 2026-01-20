package com.github.albertocavalcante.gvy.gls.engine.impl.native.features

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.adapters.ParseUnit
import com.github.albertocavalcante.gvy.gls.engine.api.CompletionService
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

class NativeCompletionService(
    private val compilationService: GroovyCompilationService,
    private val semanticResolver: SemanticTypeResolver,
) : CompletionService {

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
                    semanticResolver = semanticResolver,
                    content = content,
                )
            }
                .onFailure { throwable ->
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is Error -> throw throwable
                        else ->
                            logger.error(throwable) {
                                "Error getting completions for URI: $uri at position: ${position.line}:${position.character}"
                            }
                    }
                }
                .getOrDefault(emptyList())

        return Either.forLeft(items)
    }
}

private val logger = KotlinLogging.logger {}
