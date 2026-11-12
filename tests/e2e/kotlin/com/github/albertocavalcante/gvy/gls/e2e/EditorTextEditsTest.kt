// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.EditorPositionEncoding
import com.github.albertocavalcante.gvy.gls.testing.client.EditorTextEdits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Test

class EditorTextEditsTest {
    @Test
    fun `edits use original positions and preserve same-position insert order`() {
        val edits = listOf(
            edit(0, 1, 0, 1, "X"),
            edit(0, 1, 0, 1, "Y"),
            edit(0, 1, 0, 2, "Z"),
            edit(0, 3, 0, 4, "!"),
        )
        assertThat(EditorTextEdits.apply("abcd", edits, EditorPositionEncoding.UTF16)).isEqualTo("aXYZc!")
    }

    @Test
    fun `negotiated offsets count UTF8 UTF16 and UTF32 units`() {
        val text = "A😀B\r\nC"
        for ((encoding, end) in listOf(
            EditorPositionEncoding.UTF8 to 5,
            EditorPositionEncoding.UTF16 to 3,
            EditorPositionEncoding.UTF32 to 2,
        )) {
            assertThat(EditorTextEdits.apply(text, listOf(edit(0, 1, 0, end, "!")), encoding))
                .describedAs(encoding.name).isEqualTo("A!B\r\nC")
        }
    }

    @Test
    fun `positions past line end clamp and line endings stay intact`() {
        val edits = listOf(edit(0, 999, 1, 1, "-"))
        assertThat(EditorTextEdits.apply("ab\r\ncd", edits, EditorPositionEncoding.UTF16)).isEqualTo("ab-d")
    }

    @Test
    fun `invalid or overlapping changes fail without a partial result`() {
        val invalid = listOf(
            listOf(edit(0, 1, 0, 3, "x"), edit(0, 2, 0, 4, "y")),
            listOf(edit(0, 2, 0, 1, "x")),
            listOf(edit(1, 0, 1, 0, "x")),
            listOf(edit(0, -1, 0, 0, "x")),
            listOf(edit(0, 1, 0, 2, "x"), edit(0, 1, 0, 1, "y")),
        )
        invalid.forEach { edits ->
            assertThatThrownBy { EditorTextEdits.apply("abcd", edits, EditorPositionEncoding.UTF16) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `offsets inside a multicode-unit character are rejected`() {
        assertThatThrownBy {
            EditorTextEdits.apply("😀", listOf(edit(0, 1, 0, 1, "x")), EditorPositionEncoding.UTF16)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            EditorTextEdits.apply("😀", listOf(edit(0, 2, 0, 2, "x")), EditorPositionEncoding.UTF8)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `many edits on one line scan source a bounded number of times`() {
        val text = CountingText("a".repeat(4096))
        val edits = List(1000) { edit(0, 2048, 0, 2048, "x") }

        val result = EditorTextEdits.apply(text, edits, EditorPositionEncoding.UTF16)

        assertThat(result.length).isEqualTo(5096)
        assertThat(text.reads).isLessThan(8 * text.length + 10 * edits.size)
    }

    private class CountingText(private val value: String) : CharSequence {
        var reads = 0
        override val length: Int get() = value.length
        override fun get(index: Int): Char {
            reads++
            return value[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)
    }

    private fun edit(sl: Int, sc: Int, el: Int, ec: Int, text: String) =
        TextEdit(Range(Position(sl, sc), Position(el, ec)), text)
}
