// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstructorCompletionCopyTest {
    @Test
    fun `constructor recovery projects lexer columns and preserves CRLF source bytes`() {
        val source = "/* 😀 */\r\nclass Greeter {}\r\n/* 😀 */ new Gr"
        val index = TextIndex(source)

        val copy = CompletionContextDetector.constructorCompletionCopy(index, Position(2, 15))

        assertEquals(source + CompletionProvider.DUMMY_IDENTIFIER + "()", copy)
        assertEquals("/* 😀 */\r\nclass Greeter {}\r\n/* 😀 */ new Gr", index.text)
    }

    @Test
    fun `ordinary dummy insertion preserves original CRLF bytes`() {
        val source = "class Greeter {}\r\nnew Greeter().gr"

        val copy = CompletionContextDetector.insertDummyIdentifier(source, 1, 16, withDef = false)

        assertEquals(source + CompletionProvider.DUMMY_IDENTIFIER, copy)
    }

    @Test
    fun `constructor recovery is not applied to comment or string contents`() {
        val inputs = listOf(
            "// new Gr|",
            "/* new Gr| */",
            "/* new Gr|",
            "def value = 'new Gr|'",
            "def value = 'new Gr|",
            "def value = \"new Gr|\"",
            "def value = /new Gr|/",
        )
        inputs.forEach { source ->
            assertNull(copyAtMarker(source), "No constructor repair inside $source")
        }
    }

    @Test
    fun `existing constructor parentheses are not duplicated`() {
        assertNull(copyAtMarker("new Gr|()"))
    }

    @Test
    fun `recovery retains identifier suffixes and qualified type spelling`() {
        assertEquals(
            "new Gr" + CompletionProvider.DUMMY_IDENTIFIER + "eeter()",
            copyAtMarker("new Gr|eeter"),
        )
        assertEquals(
            "new example.Gr" + CompletionProvider.DUMMY_IDENTIFIER + "()",
            copyAtMarker("new example.Gr|"),
        )
    }

    @Test
    fun `an earlier new expression cannot justify an unrelated completion suffix`() {
        assertNull(copyAtMarker("new Old(); Gr|"))
        assertNull(copyAtMarker("new Old().value; Gr|"))
        assertNull(copyAtMarker("new example..Gr|"))
    }

    @Test
    fun `constructor token scanning propagates cancellation`() {
        assertThrows<CancellationException> {
            CompletionContextDetector.constructorCompletionCopy(TextIndex("new Gr"), Position(0, 6)) {
                throw CancellationException("cancelled")
            }
        }
    }

    private fun copyAtMarker(marked: String): String? {
        val cursor = marked.indexOf('|')
        val source = marked.replace("|", "")
        val index = TextIndex(source)
        return CompletionContextDetector.constructorCompletionCopy(index, index.positionOf(cursor))
    }
}
