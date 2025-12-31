package com.github.albertocavalcante.groovylsp.engine.impl.native.features

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.CompletionService
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

class NativeCompletionService(private val compilationService: GroovyCompilationService) : CompletionService {

    override suspend fun getCompletions(
        params: CompletionParams,
        context: ParseUnit?,
        content: String,
    ): Either<List<CompletionItem>, CompletionList> {
        val uri = params.textDocument.uri
        val position = params.position

        // Delegate to legacy provider
        val items = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = position.line,
            character = position.character,
            compilationService = compilationService,
            content = content,
        )

        return Either.forLeft(items)
    }
}
