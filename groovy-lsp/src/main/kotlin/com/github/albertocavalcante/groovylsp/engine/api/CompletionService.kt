package com.github.albertocavalcante.groovylsp.engine.api

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Service for code completion.
 * Abstracts the logic for generating completion items.
 */
interface CompletionService {
    /**
     * Get completion items.
     *
     * @param params LSP Completion parameters (position, trigger character)
     * @param context Current ParseUnit (optional, as some implementations like Native might re-parse)
     * @param content Current file content (crucial for re-parsing/dummy insertion)
     * @return Either list of items or full CompletionList
     */
    suspend fun getCompletions(
        params: CompletionParams,
        context: ParseUnit?,
        content: String,
    ): Either<List<CompletionItem>, CompletionList>
}
