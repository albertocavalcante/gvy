package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.BlockComment
import com.github.albertocavalcante.groovyparser.ast.Comment
import com.github.albertocavalcante.groovyparser.ast.JavadocComment
import com.github.albertocavalcante.groovyparser.ast.LineComment

/**
 * Parses comments from Groovy source code using position-based extraction.
 *
 * This follows the OpenRewrite approach: extract "gaps" between known AST positions,
 * then parse comments from those gaps. This is more robust than character-by-character
 * scanning because it uses the Groovy parser's own position information as anchors.
 *
 * The parser maintains a cursor position and extracts comments from the source text
 * between the cursor and the next AST node's position.
 */
internal class SourcePositionCommentParser(private val source: String) {

    /** Line offsets for quick position-to-index conversion */
    private val lineOffsets: IntArray = computeLineOffsets()

    companion object {
        private const val BLOCK_COMMENT_START_LENGTH = 2 // Length of "/*"
        private const val BLOCK_COMMENT_END_LENGTH = 2 // Length of "*/"
        private const val TRIPLE_QUOTE_LENGTH = 3 // Length of """ or '''
        private const val ESCAPE_SEQUENCE_LENGTH = 2 // Length of "\X" escape sequences
    }

    /**
     * Extracts all comments from the source between the given positions.
     *
     * @param fromLine start line (1-based, inclusive)
     * @param fromColumn start column (1-based, inclusive)
     * @param toLine end line (1-based, exclusive)
     * @param toColumn end column (1-based, exclusive)
     * @return list of comments found in the gap
     */
    fun extractCommentsBetween(fromLine: Int, fromColumn: Int, toLine: Int, toColumn: Int): List<Comment> {
        val fromIndex = positionToIndex(fromLine, fromColumn)
        val toIndex = positionToIndex(toLine, toColumn)

        if (fromIndex < 0 || toIndex < 0 || fromIndex >= toIndex) {
            return emptyList()
        }

        return parseComments(fromIndex, toIndex)
    }

    /**
     * Extracts all comments before the given position.
     */
    fun extractCommentsBefore(line: Int, column: Int): List<Comment> {
        val toIndex = positionToIndex(line, column)
        if (toIndex <= 0) return emptyList()
        return parseComments(0, toIndex)
    }

    /**
     * Extracts all comments from the entire source.
     */
    fun extractAllComments(): List<Comment> = parseComments(0, source.length)

    /**
     * Parses comments from the source substring.
     */
    private fun parseComments(fromIndex: Int, toIndex: Int): List<Comment> {
        val comments = mutableListOf<Comment>()
        var i = fromIndex.coerceAtLeast(0)
        val end = toIndex.coerceAtMost(source.length)

        while (i < end) {
            // Skip whitespace
            while (i < end && source[i].isWhitespace()) i++

            if (i >= end) break

            when {
                // Line comment: //
                i + 1 < end && source[i] == '/' && source[i + 1] == '/' -> {
                    val result = parseLineComment(i)
                    comments.add(result.comment)
                    i = result.endIndex
                }
                // Block comment: /* or /**
                i + 1 < end && source[i] == '/' && source[i + 1] == '*' -> {
                    val result = parseBlockComment(i)
                    comments.add(result.comment)
                    i = result.endIndex
                }
                // String literal - skip to avoid false positives
                source[i] == '"' || source[i] == '\'' -> {
                    i = skipStringLiteral(i)
                }
                // Regex literal - skip (starts with ~/ or / after certain tokens)
                source[i] == '/' && i + 1 < end && source[i + 1] != '/' && source[i + 1] != '*' -> {
                    // Could be division or regex; skip one char to be safe
                    i++
                }
                else -> {
                    i++
                }
            }
        }

        return comments
    }

    private fun parseLineComment(startIndex: Int): ParseResult {
        val startPos = indexToPosition(startIndex)
        var i = startIndex + 2 // Skip //
        val content = StringBuilder()

        while (i < source.length && source[i] != '\n' && source[i] != '\r') {
            content.append(source[i])
            i++
        }

        val endPos = indexToPosition(i)
        val range = Range(startPos, endPos)
        return ParseResult(LineComment(content.toString().trim(), range), i)
    }

    private fun parseBlockComment(startIndex: Int): ParseResult {
        val startPos = indexToPosition(startIndex)
        val isJavadoc = startIndex + BLOCK_COMMENT_START_LENGTH < source.length &&
            source[startIndex + BLOCK_COMMENT_START_LENGTH] == '*' &&
            (
                startIndex + TRIPLE_QUOTE_LENGTH >= source.length ||
                    source[startIndex + TRIPLE_QUOTE_LENGTH] != '/'
                )

        var i = startIndex + BLOCK_COMMENT_START_LENGTH // Skip /*
        val content = StringBuilder()

        while (i + 1 < source.length) {
            if (source[i] == '*' && source[i + 1] == '/') {
                i += BLOCK_COMMENT_END_LENGTH // Skip */
                break
            }
            content.append(source[i])
            i++
        }

        // Handle unclosed comment at EOF
        if (i + 1 >= source.length && !source.endsWith("*/")) {
            while (i < source.length) {
                content.append(source[i])
                i++
            }
        }

        val endPos = indexToPosition(i)
        val range = Range(startPos, endPos)

        // Clean up content: remove leading * on each line for Javadoc
        val cleanContent = content.toString().trim().let { c ->
            if (isJavadoc && c.startsWith("*")) c.substring(1).trim() else c
        }

        val comment = if (isJavadoc) {
            JavadocComment(cleanContent, range)
        } else {
            BlockComment(cleanContent, range)
        }

        return ParseResult(comment, i)
    }

    private fun skipStringLiteral(startIndex: Int): Int {
        val delimiter = source[startIndex]
        var i = startIndex + 1

        // Check for triple-quoted string
        val isTriple = i + 1 < source.length &&
            source[i] == delimiter &&
            source[i + 1] == delimiter

        if (isTriple) {
            i += TRIPLE_QUOTE_LENGTH - 1 // Already at first quote, skip to after third
            // Find closing triple quotes
            while (i + TRIPLE_QUOTE_LENGTH <= source.length) {
                if (source[i] == '\\' && i + 1 < source.length) {
                    i += ESCAPE_SEQUENCE_LENGTH // Skip escape sequence
                    continue
                }
                if (source[i] == delimiter &&
                    source[i + 1] == delimiter &&
                    source[i + 2] == delimiter
                ) {
                    return i + TRIPLE_QUOTE_LENGTH
                }
                i++
            }
            return source.length // Unclosed
        } else {
            // Regular string
            while (i < source.length) {
                if (source[i] == '\\' && i + 1 < source.length) {
                    i += ESCAPE_SEQUENCE_LENGTH // Skip escape sequence
                    continue
                }
                if (source[i] == delimiter) {
                    return i + 1
                }
                if (source[i] == '\n' || source[i] == '\r') {
                    // Newline in non-triple string = end of string (malformed)
                    return i
                }
                i++
            }
            return source.length // Unclosed
        }
    }

    private fun computeLineOffsets(): IntArray {
        val offsets = mutableListOf(0) // Line 1 starts at index 0
        var i = 0
        while (i < source.length) {
            if (source[i] == '\n') {
                offsets.add(i + 1)
            } else if (source[i] == '\r') {
                if (i + 1 < source.length && source[i + 1] == '\n') {
                    i++ // Skip \n in \r\n
                }
                offsets.add(i + 1)
            }
            i++
        }
        return offsets.toIntArray()
    }

    /**
     * Converts a 1-based line and column to a 0-based source index.
     */
    private fun positionToIndex(line: Int, column: Int): Int {
        if (line < 1 || line > lineOffsets.size) return -1
        val lineStart = lineOffsets[line - 1]
        return lineStart + column - 1
    }

    /**
     * Converts a 0-based source index to a 1-based Position.
     */
    private fun indexToPosition(index: Int): Position {
        // Binary search for the line
        var low = 0
        var high = lineOffsets.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lineOffsets[mid] <= index) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        val line = low + 1 // 1-based
        val column = index - lineOffsets[low] + 1 // 1-based
        return Position(line, column)
    }

    private data class ParseResult(val comment: Comment, val endIndex: Int)
}
