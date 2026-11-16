// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensLegend

internal data class DecodedSemanticToken(
    val text: String,
    val range: Range,
    val type: String,
    val modifiers: Set<String>,
)

/** Decodes full-document tokens against the source and legend captured for this request. */
internal object SemanticTokenDecoder {
    fun decode(
        data: List<Int>,
        legend: SemanticTokensLegend,
        source: String,
        positionEncoding: String? = null,
    ): List<DecodedSemanticToken> {
        require(positionEncoding == null || positionEncoding == "utf-16") { "Unsupported position encoding" }
        require(data.size % 5 == 0) { "Semantic token data must contain complete five-integer tuples" }
        val lines = sourceLines(source)
        val result = mutableListOf<DecodedSemanticToken>()
        var previousLine = 0
        var previousStart = 0
        var previousEnd = 0

        for (offset in data.indices step 5) {
            val (deltaLine, deltaStart, length, typeIndex, modifierBits) = data.subList(offset, offset + 5)
            require(deltaLine >= 0 && deltaStart >= 0 && length > 0 && typeIndex >= 0 && modifierBits >= 0) {
                "Invalid semantic token tuple"
            }
            val line = checkedSum(previousLine, deltaLine)
            val start = if (deltaLine == 0) checkedSum(previousStart, deltaStart) else deltaStart
            val end = checkedSum(start, length)
            require(line in lines.indices) { "Semantic token line outside source" }
            val text = lines[line]
            require(end <= text.length) { "Semantic token range outside source line" }
            require(!splitsSurrogate(text, start) && !splitsSurrogate(text, end)) {
                "Semantic token range splits a surrogate pair"
            }
            require(result.isEmpty() || line > previousLine || start >= previousEnd) {
                "Semantic tokens overlap or are out of order"
            }
            require(typeIndex in legend.tokenTypes.indices) { "Semantic token type outside legend" }
            val validModifierCount = minOf(legend.tokenModifiers.size, 31)
            require(modifierBits.toLong() ushr validModifierCount == 0L) {
                "Semantic token modifier outside legend"
            }
            val modifiers = (0 until validModifierCount)
                .filter { index -> modifierBits and (1 shl index) != 0 }
                .mapTo(linkedSetOf()) { index -> legend.tokenModifiers[index] }
            result += DecodedSemanticToken(
                text.substring(start, end),
                Range(Position(line, start), Position(line, end)),
                legend.tokenTypes[typeIndex],
                modifiers,
            )
            previousLine = line
            previousStart = start
            previousEnd = end
        }
        return result
    }

    private fun checkedSum(first: Int, second: Int): Int {
        val sum = first.toLong() + second
        require(sum <= Int.MAX_VALUE) { "Semantic token coordinate overflow" }
        return sum.toInt()
    }

    private fun splitsSurrogate(line: String, offset: Int): Boolean = offset > 0 && offset < line.length &&
        Character.isHighSurrogate(line[offset - 1]) && Character.isLowSurrogate(line[offset])

    private fun sourceLines(source: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < source.length) {
            if (source[index] == '\r' || source[index] == '\n') {
                lines += source.substring(start, index)
                if (source[index] == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                start = index + 1
            }
            index++
        }
        lines += source.substring(start)
        return lines
    }
}
