// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectionRangeCrossingFallbackTest {
    @Test
    fun `a shorter closing line cannot hide a structural multiline call`() {
        val source = "receiver.aVeryLongMethodName(\n    value) // note\n"
        val call = Range(Position(0, 0), Position(1, 10))
        val selected = SelectionRangeBuilder(TextIndex(source), listOf(call))
            .build(listOf(Position(1, 5))) {}.single()
        val ranges = chain(selected)
        assertTrue(call in ranges)
        assertFalse(Range(Position(1, 0), Position(1, 18)) in ranges)
    }

    @Test
    fun `compatible physical lines remain useful between a word and its call`() {
        val source = "call(\n    value\n)\n"
        val call = Range(Position(0, 0), Position(2, 1))
        val ranges = chain(
            SelectionRangeBuilder(TextIndex(source), listOf(call))
                .build(listOf(Position(1, 5))) {}.single(),
        )
        assertTrue(Range(Position(1, 0), Position(1, 9)) in ranges)
        assertTrue(call in ranges)
    }

    @Test
    fun `public expansion includes a multiline call ending before a comment`(): Unit = runBlocking {
        val source = "def result = receiver.aVeryLongMethodName(\n    value) // note\n"
        val uri = URI.create("file:///SelectionCall.groovy")
        val index = TextIndex(source)
        val compilation = GroovyCompilationService()
        val documents = DocumentProvider().apply { put(uri, source, 7) }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            compilation.compile(uri, source)
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val params = SelectionRangeParams().apply {
                textDocument = TextDocumentIdentifier(uri.toString())
                positions = listOf(Position(1, 5))
            }
            val selection = assertNotNull(service.selectionRange(params).get(5, TimeUnit.SECONDS)).single()
            val expected = Range(
                index.positionOf(source.indexOf("receiver")),
                index.positionOf(source.indexOf(')') + 1),
            )
            assertTrue(expected in chain(selection), "The call must remain a selection before the trailing comment")
        } finally {
            scope.cancel()
            compilation.clearCaches()
        }
    }

    private fun chain(first: SelectionRange): List<Range> = buildList {
        var current: SelectionRange? = first
        while (current != null) {
            assertTrue(size <= SelectionRangeBuilder.MAX_SELECTION_DEPTH)
            add(current.range)
            current = current.parent
        }
    }
}
