// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.engine.impl.rewrite

import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.SymbolInfo
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import com.github.albertocavalcante.groovyparser.provider.RewriteParserProvider
import com.github.albertocavalcante.gvy.gls.engine.adapters.RewriteParserAdapter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.api.model.Range as ParserRange

class RewriteRangeIntegrationTest {
    private val uri = "file:///example.groovy"

    @Test
    fun `adapter and hover expose exact zero based ranges`() = runTest {
        val source = "// comment\nclass Example {\n    String name\n}"
        val unit = RewriteParserProvider().parse(source)
        val adapter = RewriteParserAdapter(unit, uri)
        val expected = Range(Position(1, 0), Position(3, 1))
        assertEquals(expected, adapter.allSymbols().single { it.name == "Example" }.range)
        assertEquals(expected, assertNotNull(adapter.nodeAt(Position(1, 0))).range)
        val hover = OpenRewriteLanguageSession(adapter).features.hoverProvider
            .getHover(HoverParams(TextDocumentIdentifier(uri), Position(1, 0)))
        assertEquals(expected, hover.range)
    }

    @Test
    fun `unavailable parser ranges do not create navigable symbols or hover`() = runTest {
        val unit = mockk<ParseUnit>()
        every { unit.isSuccessful } returns true
        every { unit.diagnostics() } returns emptyList()
        every { unit.symbols() } returns listOf(SymbolInfo("Example", SymbolKind.CLASS, ParserRange.EMPTY))
        every { unit.nodeAt(any()) } returns NodeInfo(NodeKind.CLASS, "Example", ParserRange.EMPTY)
        val adapter = RewriteParserAdapter(unit, uri)
        assertTrue(adapter.allSymbols().isEmpty())
        assertNull(adapter.nodeAt(Position(0, 0)))
        val hover = OpenRewriteLanguageSession(adapter).features.hoverProvider
            .getHover(HoverParams(TextDocumentIdentifier(uri), Position(0, 0)))
        assertNull(hover.range)
        assertEquals("", hover.contents.right.value)
    }
}
