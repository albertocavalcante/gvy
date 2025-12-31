package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.CompletionProvider
import com.github.albertocavalcante.groovylsp.engine.api.CompletionService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

class UnifiedCompletionProvider(
    private val parseUnit: ParseUnit,
    private val completionService: CompletionService,
    private val content: String,
) : CompletionProvider {

    override suspend fun getCompletion(params: CompletionParams): Either<List<CompletionItem>, CompletionList> =
        completionService.getCompletions(params, parseUnit, content)
}
