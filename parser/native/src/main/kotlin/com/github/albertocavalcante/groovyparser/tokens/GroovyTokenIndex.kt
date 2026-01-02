package com.github.albertocavalcante.groovyparser.tokens

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.slf4j.LoggerFactory

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
        private val logger = LoggerFactory.getLogger(GroovyTokenIndex::class.java)

        private val LINE_COMMENT_NAMES = setOf("SINGLE_LINE_COMMENT", "SH_COMMENT")
        private val STRING_LITERAL_NAMES = setOf("StringLiteral", "SQ_STRING", "DQ_STRING", "TQS", "StringLiteralPart")

        /** Collects spans during token processing. */
        private class SpanCollector {
            private val spans = mutableListOf<TokenSpan>()
            fun add(span: TokenSpan) = spans.add(span)
            fun firstOrNull() = spans.firstOrNull()
            fun lastOrNull() = spans.lastOrNull()
            fun toSortedList(): List<TokenSpan> = spans.sortedBy { it.start }
        }

        /** Build index from source using Groovy lexer. */
        fun build(source: String): GroovyTokenIndex {
            val collector = SpanCollector()

            val lexer = createLexer(source) ?: return GroovyTokenIndex(emptyList())

            handleShebang(source, collector)
            processAllTokens(lexer, collector)

            return GroovyTokenIndex(collector.toSortedList())
        }

        private fun createLexer(source: String): GroovyLangLexer? = try {
            GroovyLangLexer(CharStreams.fromString(source))
        } catch (e: Throwable) {
            logger.debug("Failed to initialize GroovyLangLexer", e)
            null
        }

        private fun handleShebang(source: String, collector: SpanCollector) {
            if (source.startsWith("#!")) {
                val firstNewLine = source.indexOf('\n')
                val end = if (firstNewLine != -1) firstNewLine else source.length
                collector.add(TokenSpan(0, end, TokenContext.LineComment))
            }
        }

        @Suppress("TooGenericExceptionCaught") // Lexer can throw various ANTLR exceptions
        private fun processAllTokens(
            lexer: GroovyLangLexer,
            collector: SpanCollector,
        ) {
            try {
                while (true) {
                    val token = fetchNextToken(lexer, collector) ?: break
                    if (token.type == Token.EOF) break

                    processToken(token, lexer, collector)
                }
            } catch (e: Throwable) {
                logger.debug("Ignoring throwable during token indexing for resiliency", e)
            }
        }

        private fun fetchNextToken(lexer: GroovyLangLexer, collector: SpanCollector): Token? = try {
            lexer.nextToken()
        } catch (e: Throwable) {
            logger.debug("Lexer error at offset {}: {}", collector.lastOrNull()?.end ?: 0, e.message)
            null
        }

        private fun processToken(token: Token, lexer: GroovyLangLexer, collector: SpanCollector) {
            val symbolicName = lexer.vocabulary.getSymbolicName(token.type) ?: ""
            val text = token.text ?: ""

            val context = mapTokenToContext(symbolicName, text)
            if (context == TokenContext.Code) return

            // Avoid adding a duplicate span for a shebang if it was already handled manually.
            if (token.startIndex == 0 && collector.firstOrNull()?.start == 0) {
                return
            }
            collector.add(TokenSpan(token.startIndex, token.stopIndex + 1, context))
        }

        private fun mapTokenToContext(symbolicName: String, text: String): TokenContext = when {
            symbolicName in LINE_COMMENT_NAMES ||
                (symbolicName == "NL" && (text.startsWith("//") || text.startsWith("#!"))) ->
                TokenContext.LineComment

            symbolicName == "DELIMITED_COMMENT" ||
                (symbolicName == "NL" && text.startsWith("/*")) ->
                TokenContext.BlockComment

            symbolicName in STRING_LITERAL_NAMES -> TokenContext.StringLiteral

            symbolicName.startsWith("GString") ||
                symbolicName.contains("GSTRING_MODE") ->
                TokenContext.GString

            else -> TokenContext.Code
        }
    }
}
