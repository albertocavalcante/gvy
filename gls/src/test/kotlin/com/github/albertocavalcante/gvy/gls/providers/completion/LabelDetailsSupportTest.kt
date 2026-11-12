// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Overloads told apart only by package stay distinguishable whether or not the client renders labelDetails. */
class LabelDetailsSupportTest {
    private val uri = URI.create("file:///Choose.groovy")
    private val compilation = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val source = """
        class Greeter {
            String choose(java.util.Date value) { 'util' }
            String choose(java.sql.Date value) { 'sql' }
        }
        new Greeter().ch
    """.trimIndent()

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.clearCaches()
    }

    @Test
    fun `a client with labelDetails support gets the package as a label hint`(): Unit = runBlocking {
        val choose = complete(labelDetailsSupport = true)

        assertEquals(setOf("java.util.Date", "java.sql.Date"), choose.map { it.labelDetails?.description }.toSet())
        assertEquals(listOf("String choose(Date value)"), choose.map { it.detail }.distinct())
        assertEquals(listOf("choose"), choose.map { it.label }.distinct())
    }

    @Test
    fun `a client without labelDetails support gets the package in detail and the same label`(): Unit = runBlocking {
        val choose = complete(labelDetailsSupport = false)

        choose.forEach { assertNull(it.labelDetails) }
        assertEquals(
            setOf("String choose(Date value) (java.util.Date)", "String choose(Date value) (java.sql.Date)"),
            choose.map { it.detail }.toSet(),
        )
        assertEquals(listOf("choose"), choose.map { it.label }.distinct())
    }

    private suspend fun complete(labelDetailsSupport: Boolean): List<CompletionItem> {
        documents.put(uri, source, 1)
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        service.updateLabelDetailsSupport(labelDetailsSupport)
        val lines = source.lines()
        val result = service.completion(
            CompletionParams(TextDocumentIdentifier(uri.toString()), Position(lines.lastIndex, lines.last().length)),
        ).await()
        val items = if (result.isLeft) result.left else result.right.items
        return items.filter { it.label == "choose" && it.kind == CompletionItemKind.Method }
            .also { assertEquals(2, it.size, "found ${items.map { item -> item.label }}") }
    }
}
