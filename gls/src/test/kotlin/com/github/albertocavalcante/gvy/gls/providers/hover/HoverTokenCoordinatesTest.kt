// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HoverTokenCoordinatesTest {
    @Test
    fun `code after a two emoji comment remains hoverable at its first character`() {
        val source = "// 😀😀\nString value = ''"
        val index = TextIndex(source)
        val tokens = GroovyTokenIndex.build(source)
        val position = Position(1, 0)

        assertEquals(8, index.offsetOf(position))
        assertFalse(tokens.isInComment(8))
        assertTrue(isHoverPosition(index, position, tokens))
    }

    @Test
    fun `a comment after an emoji comment rejects hover at its first slash`() {
        val source = "// 😀\n// comment"
        val index = TextIndex(source)
        val tokens = GroovyTokenIndex.build(source)
        val position = Position(1, 0)

        assertEquals(6, index.offsetOf(position))
        assertTrue(tokens.isInComment(6))
        assertFalse(isHoverPosition(index, position, tokens))
    }

    @Test
    fun `string whitespace after a two emoji comment remains a valid hover position`() {
        val source = "// 😀😀\n' value'"
        val index = TextIndex(source)
        val tokens = GroovyTokenIndex.build(source)
        val position = Position(1, 1)

        assertEquals(9, index.offsetOf(position))
        assertTrue(tokens.isInString(9))
        assertTrue(isHoverPosition(index, position, tokens))
    }

    @Test
    fun `native hover after a two emoji comment returns String content and its UTF16 range`(): Unit =
        runTest(timeout = 30.seconds) {
            val source = "// 😀😀\nString value = ''"
            val uri = URI.create("untitled:HoverTokenCoordinates.groovy")
            val documents = DocumentProvider()
            documents.put(uri, source, 1)
            val compilation = GroovyCompilationService(
                documentProvider = documents,
                engineConfig = EngineConfiguration(EngineType.Native),
            )
            try {
                val result = compilation.compile(uri, source)
                val parsed = assertNotNull(compilation.getParseResult(uri))
                parsed.compilationUnit.classLoader.use {
                    assertTrue(result.isSuccess)
                    val handler = HoverRequestHandler(compilation, documents)
                    val hover = assertNotNull(
                        handler.getHover(HoverParams(TextDocumentIdentifier(uri.toString()), Position(1, 0))),
                    )

                    assertTrue(hover.contents.isRight)
                    val content = assertNotNull(hover.contents.right)
                    assertTrue(content.value.isNotBlank())
                    assertContains(content.value, "String")
                    assertEquals(Range(Position(1, 0), Position(1, 6)), hover.range)
                }
            } finally {
                compilation.shutdown()
            }
        }
}
