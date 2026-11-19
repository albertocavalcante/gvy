// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensLegend
import org.junit.jupiter.api.Test

class SemanticTokenDecoderTest {
    private val legend = SemanticTokensLegend(listOf("variable", "class"), listOf("readonly", "declaration"))

    @Test
    fun `decodes current legend and exact UTF16 spans across line endings`() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val source = "class Before$newline/* 😀 */ class After$newline"
            val data = listOf(0, 6, 6, 1, 2, 1, 15, 5, 1, 3)

            val tokens = SemanticTokenDecoder.decode(data, legend, source)

            assertThat(tokens).containsExactly(
                DecodedSemanticToken("Before", range(0, 6, 12), "class", setOf("declaration")),
                DecodedSemanticToken("After", range(1, 15, 20), "class", setOf("readonly", "declaration")),
            )
            assertThat(SemanticTokenDecoder.decode(data, legend, source, "utf-16")).isEqualTo(tokens)
        }
    }

    @Test
    fun `same line deltas are relative to the previous start rather than its end`() {
        val tokens = SemanticTokenDecoder.decode(
            listOf(0, 6, 3, 1, 0, 0, 14, 3, 1, 0),
            legend,
            "class One {}; class Two {}",
        )

        assertThat(tokens).containsExactly(
            DecodedSemanticToken("One", range(0, 6, 9), "class", emptySet()),
            DecodedSemanticToken("Two", range(0, 20, 23), "class", emptySet()),
        )
    }

    @Test
    fun `rejects malformed tuples and invalid legend references`() {
        val badData = listOf(
            listOf(0, 0, 1, 0),
            listOf(-1, 0, 1, 0, 0),
            listOf(0, -1, 1, 0, 0),
            listOf(0, 0, 0, 0, 0),
            listOf(0, 0, -1, 0, 0),
            listOf(0, 0, 1, -1, 0),
            listOf(0, 0, 1, 2, 0),
            listOf(0, 0, 1, 0, -1),
            listOf(0, 0, 1, 0, 4),
        )
        badData.forEach { data ->
            assertThatThrownBy { SemanticTokenDecoder.decode(data, legend, "abc") }
                .describedAs("data %s", data).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects corrupt coordinates overflow overlap and surrogate splits`() {
        val badData = listOf(
            listOf(2, 0, 1, 0, 0),
            listOf(0, 4, 1, 0, 0),
            listOf(0, 3, 2, 0, 0),
            listOf(0, 1, 1, 0, 0),
            listOf(0, 0, 2, 0, 0),
            listOf(0, 0, 1, 0, 0, 0, 0, 1, 0, 0),
            listOf(0, 0, 1, 0, 0, Int.MAX_VALUE, 0, 1, 0, 0, 1, 0, 1, 0, 0),
            listOf(0, 0, 1, 0, 0, 0, Int.MAX_VALUE, 1, 0, 0),
        )
        badData.forEach { data ->
            assertThatThrownBy { SemanticTokenDecoder.decode(data, legend, "a😀b\r\nx") }
                .describedAs("data %s", data).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects unsupported negotiated encoding`() {
        assertThatThrownBy { SemanticTokenDecoder.decode(emptyList(), legend, "", "utf-8") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun range(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))
}
