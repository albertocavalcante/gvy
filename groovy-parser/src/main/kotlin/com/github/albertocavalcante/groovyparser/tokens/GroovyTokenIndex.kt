package com.github.albertocavalcante.groovyparser.tokens

import arrow.core.Option
import arrow.core.none
import arrow.core.some

/**
 * Token classification at a given offset.
 */
sealed interface TokenContext {
    data object Code : TokenContext
    data object LineComment : TokenContext
    data object BlockComment : TokenContext
    data object StringLiteral : TokenContext
    data object GString : TokenContext
}

/**
 * Efficient token index for cursor context queries.
 *
 * Provides O(log N) lookup for token classification at any source offset.
 */
class GroovyTokenIndex private constructor(private val spans: List<TokenSpan>) {
    /**
     * Represents a token span in the source code.
     *
     * The span uses half-open interval semantics: `[start, end)`.
     *
     * @property start Inclusive start offset (0-based)
     * @property end Exclusive end offset (0-based)
     * @property context The token classification for this span
     */
    data class TokenSpan(val start: Int, val end: Int, val context: TokenContext)

    /** Query token context at offset. */
    fun contextAt(offset: Int): Option<TokenContext> {
        if (spans.isEmpty()) return none()

        val idx = spans.binarySearchBy(offset) { it.start }
        val insertionPoint = if (idx >= 0) idx else -(idx + 1) - 1

        if (insertionPoint < 0 || insertionPoint >= spans.size) return none()

        val span = spans[insertionPoint]
        return if (offset in span.start until span.end) span.context.some() else none()
    }

    fun isInComment(offset: Int): Boolean =
        contextAt(offset).fold({ false }) { it is TokenContext.LineComment || it is TokenContext.BlockComment }

    fun isInString(offset: Int): Boolean =
        contextAt(offset).fold({ false }) { it is TokenContext.StringLiteral || it is TokenContext.GString }

    fun isInCommentOrString(offset: Int): Boolean = isInComment(offset) || isInString(offset)

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(GroovyTokenIndex::class.java)

        /** Build index from source using Groovy lexer. */
        @Suppress("TooGenericExceptionCaught")
        fun build(source: String): GroovyTokenIndex {
            val spans = mutableListOf<TokenSpan>()

            val lexer = try {
                org.apache.groovy.parser.antlr4.GroovyLangLexer(
                    groovyjarjarantlr4.v4.runtime.CharStreams.fromString(source),
                )
            } catch (e: Throwable) {
                logger.debug("Failed to initialize GroovyLangLexer", e)
                return GroovyTokenIndex(emptyList())
            }

            // Handle shebang manually if lexer skips it
            if (source.startsWith("#!")) {
                val firstNewLine = source.indexOf('\n')
                val end = if (firstNewLine != -1) firstNewLine else source.length
                spans.add(TokenSpan(0, end, TokenContext.LineComment))
            }

            try {
                var finished = false
                while (!finished) {
                    val token = try {
                        lexer.nextToken()
                    } catch (e: Throwable) {
                        logger.debug("Lexer error at offset {}: {}", spans.lastOrNull()?.end ?: 0, e.message)
                        null
                    }

                    if (token == null || token.type == groovyjarjarantlr4.v4.runtime.Token.EOF) {
                        finished = true
                    } else {
                        val type = token.type
                        val symbolicName = lexer.vocabulary.getSymbolicName(type) ?: ""
                        val text = token.text ?: ""

                        val context = when {
                            symbolicName in setOf("SINGLE_LINE_COMMENT", "SH_COMMENT") ||
                                (symbolicName == "NL" && (text.startsWith("//") || text.startsWith("#!"))) ->
                                TokenContext.LineComment

                            symbolicName == "DELIMITED_COMMENT" ||
                                (symbolicName == "NL" && text.startsWith("/*")) ->
                                TokenContext.BlockComment

                            symbolicName in setOf(
                                "StringLiteral",
                                "SQ_STRING",
                                "DQ_STRING",
                                "TQS",
                                "StringLiteralPart",
                            ) ->
                                TokenContext.StringLiteral

                            symbolicName.startsWith("GString") ||
                                symbolicName.contains("GSTRING_MODE") ->
                                TokenContext.GString

                            else -> TokenContext.Code
                        }

                        if (context != TokenContext.Code) {
                            // Avoid duplicates if we manually handled shebang at position 0
                            val alreadyHandledAtZero = token.startIndex == 0 &&
                                spans.any { it.start == 0 && it.context is TokenContext.LineComment }
                            if (!alreadyHandledAtZero) {
                                spans.add(TokenSpan(token.startIndex, token.stopIndex + 1, context))
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // Ignore lexer errors during indexing to keep LSP resilient but log
                logger.debug("Ignoring throwable during token indexing for resiliency", e)
            }

            return GroovyTokenIndex(spans.sortedBy { it.start })
        }
    }
}
