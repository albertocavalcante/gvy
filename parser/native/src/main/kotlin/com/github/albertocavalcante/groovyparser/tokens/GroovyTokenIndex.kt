// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.tokens

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import groovyjarjarantlr4.v4.runtime.ANTLRErrorListener
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.RecognitionException
import groovyjarjarantlr4.v4.runtime.Recognizer
import groovyjarjarantlr4.v4.runtime.Token
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError

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
class GroovyTokenIndex private constructor(
    private val spans: List<TokenSpan>,
    internal val diagnosticTokens: List<DiagnosticToken>,
    internal val complete: Boolean,
    internal val delimiterFacts: DelimiterFacts = DelimiterFacts(),
) {
    /** Lexer facts use UTF-16 offsets; channel and symbolic name retain statement boundaries. */
    internal data class DiagnosticToken(
        val type: Int,
        val symbolicName: String,
        val start: Int,
        val end: Int,
        val channel: Int,
        val context: TokenContext,
    )

    /**
     * Represents a token span in the source code.
     *
     * The span uses UTF-16 offsets with half-open interval semantics: `[start, end)`.
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
        private val logger = KotlinLogging.logger {}

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

        private fun shouldRethrow(throwable: Throwable): Boolean = throwable is Error && throwable !is GroovySyntaxError

        /** Build index from source using Groovy lexer. */
        fun build(source: String): GroovyTokenIndex {
            val collector = SpanCollector()

            val lexer = createLexer(source) ?: return GroovyTokenIndex(emptyList(), emptyList(), false)
            var lexerFailed = false
            lexer.removeErrorListeners()
            lexer.addErrorListener(object : ANTLRErrorListener<Int> {
                override fun <T : Int?> syntaxError(
                    recognizer: Recognizer<T, *>?,
                    offendingSymbol: T,
                    line: Int,
                    charPositionInLine: Int,
                    message: String?,
                    exception: RecognitionException?,
                ) {
                    lexerFailed = true
                }
            })
            val codePointToUtf16 = codePointToUtf16Offsets(source)

            handleShebang(source, collector)
            val tokens = mutableListOf<DiagnosticToken>()
            val delimiters = DelimiterFacts()
            val complete = processAllTokens(lexer, collector, codePointToUtf16, tokens, delimiters)

            return GroovyTokenIndex(collector.toSortedList(), tokens, complete && !lexerFailed, delimiters)
        }

        private fun createLexer(source: String): GroovyLangLexer? = runCatching {
            GroovyLangLexer(CharStreams.fromString(source))
        }.onFailure { throwable ->
            if (shouldRethrow(throwable)) throw throwable
            logger.debug(throwable) { "Failed to initialize GroovyLangLexer" }
        }.getOrNull()

        private fun handleShebang(source: String, collector: SpanCollector) {
            if (source.startsWith("#!")) {
                val firstNewLine = source.indexOf('\n')
                val end = if (firstNewLine != -1) firstNewLine else source.length
                collector.add(TokenSpan(0, end, TokenContext.LineComment))
            }
        }

        private fun codePointToUtf16Offsets(source: String): IntArray? {
            val codePointCount = source.codePointCount(0, source.length)
            if (codePointCount == source.length) return null

            // ANTLR indexes code points; Kotlin String and LSP offsets count UTF-16 code units.
            // Include the end boundary so half-open spans also cover a final supplementary character.
            val offsets = IntArray(codePointCount + 1)
            var utf16Offset = 0
            for (codePointOffset in 0 until codePointCount) {
                offsets[codePointOffset] = utf16Offset
                utf16Offset += Character.charCount(source.codePointAt(utf16Offset))
            }
            offsets[codePointCount] = source.length
            return offsets
        }

        private fun processAllTokens(
            lexer: GroovyLangLexer,
            collector: SpanCollector,
            codePointToUtf16: IntArray?,
            tokens: MutableList<DiagnosticToken>,
            delimiters: DelimiterFacts,
        ): Boolean = runCatching {
            var token = fetchNextToken(lexer, collector)
            while (token != null && token.type != Token.EOF) {
                val symbolicName = lexer.vocabulary.getSymbolicName(token.type) ?: ""
                val start = codePointToUtf16?.get(token.startIndex) ?: token.startIndex
                val end = codePointToUtf16?.get(token.stopIndex + 1) ?: token.stopIndex + 1
                val fact = DiagnosticToken(
                    token.type,
                    symbolicName,
                    start,
                    end,
                    token.channel,
                    mapTokenToContext(symbolicName, token.text ?: ""),
                )
                tokens.add(fact)
                delimiters.accept(fact)
                processToken(token, lexer, collector, codePointToUtf16)
                token = fetchNextToken(lexer, collector)
            }
            token?.type == Token.EOF
        }.onFailure { throwable ->
            if (shouldRethrow(throwable)) throw throwable
            logger.debug(throwable) { "Ignoring throwable during token indexing for resiliency" }
        }.getOrDefault(false)

        private fun fetchNextToken(lexer: GroovyLangLexer, collector: SpanCollector): Token? = runCatching {
            lexer.nextToken()
        }.onFailure { throwable ->
            if (shouldRethrow(throwable)) throw throwable
            logger.debug { "Lexer error at offset ${collector.lastOrNull()?.end ?: 0}: ${throwable.message}" }
        }.getOrNull()

        private fun processToken(
            token: Token,
            lexer: GroovyLangLexer,
            collector: SpanCollector,
            codePointToUtf16: IntArray?,
        ) {
            val symbolicName = lexer.vocabulary.getSymbolicName(token.type) ?: ""
            val text = token.text ?: ""

            val context = mapTokenToContext(symbolicName, text)
            if (context == TokenContext.Code) return

            // Avoid adding a duplicate span for a shebang if it was already handled manually.
            if (token.startIndex == 0 && collector.firstOrNull()?.start == 0) {
                return
            }
            val codePointEnd = token.stopIndex + 1
            val start = codePointToUtf16?.get(token.startIndex) ?: token.startIndex
            val end = codePointToUtf16?.get(codePointEnd) ?: codePointEnd
            collector.add(TokenSpan(start, end, context))
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
