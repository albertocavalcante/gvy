package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovylsp.scanner.TodoCommentScanner
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

/**
 * Provides semantic tokens for enhanced syntax highlighting, particularly for TODO comments.
 *
 * This provider implements the LSP 3.16+ semantic tokens feature to offer rich highlighting
 * beyond what TextMate grammars can provide. It focuses on TODO comments but can be extended
 * for other semantic information.
 *
 * Semantic tokens use a delta-encoded format for efficiency, representing tokens as:
 * [line, startChar, length, tokenType, tokenModifiers] where each value is relative to the previous.
 */
class SemanticTokenProvider {

    companion object {
        private val logger = LoggerFactory.getLogger(SemanticTokenProvider::class.java)

        // Token type indices (correspond to legend.tokenTypes)
        const val TOKEN_TYPE_COMMENT = 0

        // Token modifier indices (correspond to legend.tokenModifiers)
        const val MODIFIER_TODO = 0
        const val MODIFIER_FIXME = 1
        const val MODIFIER_WARNING = 2
        const val MODIFIER_ERROR = 3
        const val MODIFIER_NOTE = 4

        /**
         * Creates the semantic tokens legend that defines available token types and modifiers.
         * This legend is sent to the client during initialization.
         */
        fun createLegend(): SemanticTokensLegend = SemanticTokensLegend().apply {
            tokenTypes = listOf(
                SemanticTokenTypes.Comment, // Index 0
            )
            tokenModifiers = listOf(
                "todo", // Index 0 - For TODO patterns
                "fixme", // Index 1 - For FIXME patterns
                "warning", // Index 2 - For XXX/HACK patterns
                "error", // Index 3 - For BUG patterns
                "note", // Index 4 - For NOTE/OPTIMIZE patterns
            )
        }

        // Regex patterns for finding TODO comments in source code
        private val SINGLE_LINE_COMMENT_PATTERN = Pattern.compile("//.*", Pattern.CASE_INSENSITIVE)
        private val MULTI_LINE_COMMENT_PATTERN = Pattern.compile(
            "/\\*.*?\\*/",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE,
        )
    }

    private val todoScanner = TodoCommentScanner()

    /**
     * Data class representing a semantic token before encoding.
     */
    data class Token(val line: Int, val startChar: Int, val length: Int, val tokenType: Int, val tokenModifiers: Int)

    /**
     * Generates semantic tokens for the given source code.
     *
     * @param sourceCode The source code to analyze
     * @param uri The document URI (for logging)
     * @return SemanticTokens with delta-encoded token data
     */
    fun generateSemanticTokens(sourceCode: String, uri: String): SemanticTokens {
        logger.debug("Generating semantic tokens for $uri")

        val tokens = mutableListOf<Token>()
        val lines = sourceCode.lines()

        // Scan for TODO patterns and create semantic tokens
        scanForTodoTokens(lines, tokens)

        // Convert to delta-encoded format required by LSP
        val encodedTokens = encodeToDelta(tokens)

        logger.debug("Generated ${tokens.size} semantic tokens for $uri")

        return SemanticTokens().apply {
            data = encodedTokens
        }
    }

    /**
     * Scans for TODO patterns in comments and creates semantic tokens.
     */
    private fun scanForTodoTokens(lines: List<String>, tokens: MutableList<Token>) {
        lines.forEachIndexed { lineIndex, line ->
            // Find single-line comments
            scanSingleLineComments(line, lineIndex, tokens)

            // TODO: Add multi-line comment support for semantic tokens
            // For now, focus on single-line comments which are most common
        }
    }

    /**
     * Scans a single line for TODO patterns in comments.
     */
    private fun scanSingleLineComments(line: String, lineIndex: Int, tokens: MutableList<Token>) {
        val commentMatcher = SINGLE_LINE_COMMENT_PATTERN.matcher(line)

        if (commentMatcher.find()) {
            val commentStart = commentMatcher.start()
            val commentText = commentMatcher.group()

            // Check if this comment contains TODO patterns
            scanCommentForPatterns(commentText, lineIndex, commentStart, tokens)
        }
    }

    /**
     * Scans comment text for TODO patterns and creates semantic tokens.
     */
    private fun scanCommentForPatterns(
        commentText: String,
        lineIndex: Int,
        commentStart: Int,
        tokens: MutableList<Token>,
    ) {
        val supportedPatterns = todoScanner.getSupportedPatterns()

        for ((keyword, severity) in supportedPatterns) {
            // Create case-insensitive pattern for the keyword
            val keywordPattern = Pattern.compile("\\b$keyword\\b:?", Pattern.CASE_INSENSITIVE)
            val matcher = keywordPattern.matcher(commentText)

            while (matcher.find()) {
                val keywordStart = commentStart + matcher.start()
                val keywordLength = matcher.end() - matcher.start()
                val modifier = getModifierForKeyword(keyword)

                tokens.add(
                    Token(
                        line = lineIndex,
                        startChar = keywordStart,
                        length = keywordLength,
                        tokenType = TOKEN_TYPE_COMMENT,
                        tokenModifiers = 1 shl modifier, // Convert to bitmask
                    ),
                )

                logger.trace("Found $keyword token at line $lineIndex, char $keywordStart")
            }
        }
    }

    /**
     * Maps TODO keywords to their corresponding modifier indices.
     */
    private fun getModifierForKeyword(keyword: String): Int = when (keyword.uppercase()) {
        "TODO" -> MODIFIER_TODO
        "FIXME" -> MODIFIER_FIXME
        "XXX", "HACK" -> MODIFIER_WARNING
        "BUG" -> MODIFIER_ERROR
        "NOTE", "OPTIMIZE" -> MODIFIER_NOTE
        else -> MODIFIER_TODO // Default fallback
    }

    /**
     * Encodes tokens to delta format as required by LSP specification.
     *
     * Delta encoding means each token's position is relative to the previous token:
     * - First token: absolute line and character
     * - Subsequent tokens: line delta, character delta (or absolute if different line)
     */
    private fun encodeToDelta(tokens: List<Token>): List<Int> {
        if (tokens.isEmpty()) return emptyList()

        val encoded = mutableListOf<Int>()
        var prevLine = 0
        var prevChar = 0

        for (token in tokens.sortedWith(compareBy({ it.line }, { it.startChar }))) {
            val lineDelta = token.line - prevLine
            val charDelta = if (lineDelta == 0) token.startChar - prevChar else token.startChar

            encoded.addAll(
                listOf(
                    lineDelta,
                    charDelta,
                    token.length,
                    token.tokenType,
                    token.tokenModifiers,
                ),
            )

            prevLine = token.line
            prevChar = token.startChar
        }

        return encoded
    }

    /**
     * Generates semantic tokens for a specific range in the document.
     * This can be used for optimization when only part of the document changes.
     *
     * @param sourceCode The complete source code
     * @param startLine Start line (0-indexed)
     * @param endLine End line (0-indexed, exclusive)
     * @param uri Document URI for logging
     * @return SemanticTokens for the specified range
     */
    fun generateSemanticTokensRange(sourceCode: String, startLine: Int, endLine: Int, uri: String): SemanticTokens {
        logger.debug("Generating semantic tokens for range $startLine-$endLine in $uri")

        val lines = sourceCode.lines()
        val relevantLines = lines.subList(
            maxOf(0, startLine),
            minOf(lines.size, endLine),
        )

        val tokens = mutableListOf<Token>()

        relevantLines.forEachIndexed { relativeIndex, line ->
            val absoluteLineIndex = startLine + relativeIndex
            scanSingleLineComments(line, absoluteLineIndex, tokens)
        }

        val encodedTokens = encodeToDelta(tokens)

        logger.debug("Generated ${tokens.size} semantic tokens for range in $uri")

        return SemanticTokens().apply {
            data = encodedTokens
        }
    }

    /**
     * Checks if semantic tokens are needed for the given source code.
     * This can be used for optimization to avoid generating tokens for files without TODO comments.
     */
    fun hasSemanticTokens(sourceCode: String): Boolean = todoScanner.getSupportedPatterns().keys.any { keyword ->
        sourceCode.contains(keyword, ignoreCase = true)
    }
}
