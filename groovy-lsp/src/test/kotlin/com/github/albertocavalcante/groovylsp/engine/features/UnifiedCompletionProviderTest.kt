package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.CompletionService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedCompletionProviderTest {

    private val parseUnit = mockk<ParseUnit>()
    private val completionService = mockk<CompletionService>()
    private val content = "def foo = 1"
    private val provider = UnifiedCompletionProvider(parseUnit, completionService, content)

    @Test
    fun `getCompletion delegates to service`() {
        val position = Position(0, 5)
        val params = CompletionParams(TextDocumentIdentifier("file:///test.groovy"), position)
        val items = listOf(CompletionItem("foo"))

        coEvery { completionService.getCompletions(params, parseUnit, content) } returns Either.forLeft(items)

        val result = runBlocking {
            provider.getCompletion(params)
        }

        assertTrue(result.isLeft)
        assertEquals(items, result.left)
    }
}
