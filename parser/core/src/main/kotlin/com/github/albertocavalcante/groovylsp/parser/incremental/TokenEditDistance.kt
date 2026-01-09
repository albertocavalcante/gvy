package com.github.albertocavalcante.groovylsp.parser.incremental

/**
 * Maps positions between original and revised document versions using token-based alignment.
 *
 * This is inspired by Metals' TokenEditDistance pattern which allows navigation features
 * to work on stale buffers by aligning tokens between document versions.
 *
 * @see <a href="https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/parsing/TokenEditDistance.scala">Metals TokenEditDistance</a>
 */
class TokenEditDistance private constructor(
    private val originalLines: List<String>,
    private val revisedLines: List<String>,
    private val lineMapping: Map<Int, Int>,
    private val reverseLineMapping: Map<Int, Int>,
) {
    /**
     * Maps a position from the original document to the revised document.
     *
     * @param position Position in the original document
     * @return Corresponding position in the revised document, or the closest match
     */
    fun toRevised(position: Position): Position {
        if (originalLines.isEmpty() || revisedLines.isEmpty()) {
            return Position(0, 0)
        }

        val mappedLine = lineMapping[position.line]
        if (mappedLine != null) {
            // Direct line mapping exists
            val originalLine = originalLines.getOrNull(position.line) ?: ""
            val revisedLine = revisedLines.getOrNull(mappedLine) ?: ""
            val mappedColumn = mapColumn(originalLine, revisedLine, position.column)
            return Position(mappedLine, mappedColumn)
        }

        // No direct mapping - find closest line
        val closestLine = findClosestMappedLine(position.line, lineMapping, revisedLines)
        val maxColumn = revisedLines.getOrNull(closestLine)?.length ?: 0
        return Position(closestLine, position.column.coerceAtMost(maxColumn))
    }

    /**
     * Maps a position from the revised document back to the original document.
     *
     * @param position Position in the revised document
     * @return Corresponding position in the original document, or the closest match
     */
    fun toOriginal(position: Position): Position {
        if (originalLines.isEmpty() || revisedLines.isEmpty()) {
            return Position(0, 0)
        }

        val mappedLine = reverseLineMapping[position.line]
        if (mappedLine != null) {
            val originalLine = originalLines.getOrNull(mappedLine) ?: ""
            val revisedLine = revisedLines.getOrNull(position.line) ?: ""
            val mappedColumn = mapColumn(revisedLine, originalLine, position.column)
            return Position(mappedLine, mappedColumn)
        }

        val closestLine = findClosestMappedLine(position.line, reverseLineMapping, originalLines)
        val maxColumn = originalLines.getOrNull(closestLine)?.length ?: 0
        return Position(closestLine, position.column.coerceAtMost(maxColumn))
    }

    private fun mapColumn(fromLine: String, toLine: String, column: Int): Int {
        if (fromLine == toLine) return column
        if (column >= fromLine.length) return toLine.length.coerceAtLeast(0)

        // Find token at column in fromLine and locate it in toLine
        val tokenInfo = findTokenAt(fromLine, column)
        if (tokenInfo != null) {
            val (token, tokenStart) = tokenInfo
            val indexInToLine = toLine.indexOf(token)
            if (indexInToLine >= 0) {
                val offsetInToken = column - tokenStart
                return indexInToLine + offsetInToken
            }
        }

        // Fallback: proportional mapping
        return (column.toDouble() / fromLine.length * toLine.length).toInt()
    }

    private fun findTokenAt(line: String, column: Int): Pair<String, Int>? {
        if (column >= line.length) return null

        var start = column
        var end = column

        // Expand to word boundaries
        while (start > 0 && line[start - 1].isLetterOrDigit()) start--
        while (end < line.length && line[end].isLetterOrDigit()) end++

        return if (start < end) Pair(line.substring(start, end), start) else null
    }

    private fun findClosestMappedLine(line: Int, mapping: Map<Int, Int>, targetLines: List<String>): Int {
        if (mapping.isEmpty()) return line.coerceIn(0, (targetLines.size - 1).coerceAtLeast(0))

        var closest = mapping.keys.first()
        var minDistance = kotlin.math.abs(line - closest)

        for (key in mapping.keys) {
            val distance = kotlin.math.abs(line - key)
            if (distance < minDistance) {
                minDistance = distance
                closest = key
            }
        }

        return mapping[closest] ?: line
    }

    companion object {
        /**
         * Creates a TokenEditDistance from two text documents.
         */
        fun fromTexts(original: String, revised: String): TokenEditDistance {
            val originalLines = original.lines()
            val revisedLines = revised.lines()

            val lineMapping = mutableMapOf<Int, Int>()
            val reverseLineMapping = mutableMapOf<Int, Int>()

            // Use LCS-based diff to find matching lines
            val lcs = longestCommonSubsequence(originalLines, revisedLines)

            for ((origIdx, revIdx) in lcs) {
                lineMapping[origIdx] = revIdx
                reverseLineMapping[revIdx] = origIdx
            }

            return TokenEditDistance(originalLines, revisedLines, lineMapping, reverseLineMapping)
        }

        /**
         * Finds the longest common subsequence of lines between two documents.
         * Returns pairs of (originalIndex, revisedIndex) for matching lines.
         */
        private fun longestCommonSubsequence(original: List<String>, revised: List<String>): List<Pair<Int, Int>> {
            val m = original.size
            val n = revised.size

            // DP table
            val dp = Array(m + 1) { IntArray(n + 1) }

            for (i in 1..m) {
                for (j in 1..n) {
                    dp[i][j] = if (original[i - 1] == revised[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
                }
            }

            // Backtrack to find the actual LCS
            val result = mutableListOf<Pair<Int, Int>>()
            var i = m
            var j = n

            while (i > 0 && j > 0) {
                when {
                    original[i - 1] == revised[j - 1] -> {
                        result.add(Pair(i - 1, j - 1))
                        i--
                        j--
                    }
                    dp[i - 1][j] > dp[i][j - 1] -> i--
                    else -> j--
                }
            }

            result.reverse()
            return result
        }
    }
}

/**
 * Represents a position in a document.
 */
data class Position(val line: Int, val column: Int)
