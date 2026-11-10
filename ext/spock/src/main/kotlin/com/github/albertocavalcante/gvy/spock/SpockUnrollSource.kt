package com.github.albertocavalcante.gvy.spock

import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression

/**
 * Maps offsets in a runtime unroll pattern to UTF-16 offsets in the document for one string
 * literal. Every pattern offset, including the end offset, has a source offset; a range ending
 * before an escape ends at the escape's first character.
 */
class UnrollLiteral internal constructor(
    val sourceRange: IntRange,
    /** The decoded pattern this literal maps; ranges must come from parsing exactly this value. */
    val value: String,
    private val offsets: IntArray,
) {
    /** Null when [patternOffset] is outside this literal's pattern; in-bounds offsets are not checked. */
    fun sourceOffset(patternOffset: Int): Int? = offsets.getOrNull(patternOffset)

    /**
     * The source span covering pattern offsets [range] (exclusive end), or null when out of bounds.
     * Only bounds are checked: a range parsed from a different pattern can still return a wrong span,
     * so callers must parse [value] itself.
     */
    fun sourceRange(range: IntRange): IntRange? {
        val start = offsets.getOrNull(range.first) ?: return null
        val end = offsets.getOrNull(range.last + 1) ?: return null
        return if (range.first <= range.last + 1) start until end else null
    }
}

/**
 * Locates unroll pattern literals in source text without text search. A mapping is returned only
 * when decoding the located literal reproduces the compiler's value exactly; otherwise null, so a
 * stale document, an unsupported spelling or a position mismatch never yields a nearby guess.
 *
 * Supported spellings: `'…'`, `"…"`, `'''…'''` and `"""…"""` with standard, octal and unicode
 * escapes, line continuations and CRLF. Slashy `/…/` literals are mapped only when they contain
 * nothing but plain characters and `\/`; slashy unicode escapes, continuations and CR normalisation
 * are not modelled and yield null. Dollar-slashy strings and GStrings are unmapped.
 *
 * One instance per document text: line starts are computed once, so mapping every feature in a
 * file stays linear in its size.
 */
class SpockUnrollSource(private val source: String) {
    private val lineStarts: IntArray = buildList {
        add(0)
        source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    /** The string literal naming [method], found with Groovy's lexer between its start and its body. */
    fun featureName(method: MethodNode): UnrollLiteral? {
        val body = method.code ?: return null
        val start = offset(method.lineNumber, method.columnNumber) ?: return null
        val end = offset(body.lineNumber, body.columnNumber) ?: return null
        if (start >= end) return null
        val header = source.substring(start, end)
        val token = nameToken(header) ?: return null
        val tokenStart = start + header.offsetByCodePoints(0, token.startIndex)
        return decode(token.text, tokenStart)?.takeIf { it.first == method.name }?.second
    }

    /** The literal of an annotation member, located by the constant's own source position. */
    fun annotationValue(value: ConstantExpression): UnrollLiteral? {
        val expected = value.value as? String ?: return null
        val range = range(value) ?: return null
        return decode(source.substring(range.first, range.last + 1), range.first)
            ?.takeIf { it.first == expected }?.second
    }

    private fun range(node: ASTNode): IntRange? {
        val start = offset(node.lineNumber, node.columnNumber) ?: return null
        val end = offset(node.lastLineNumber, node.lastColumnNumber) ?: return null
        return if (start < end) start until end else null
    }

    /** 1-based line and code-point column (as the Groovy lexer counts them) to a UTF-16 offset. */
    private fun offset(line: Int, column: Int): Int? {
        if (line < 1 || column < 1 || line > lineStarts.size) return null
        val lineStart = lineStarts[line - 1]
        val lineEnd = if (line < lineStarts.size) lineStarts[line] else source.length
        if (column - 1 > source.codePointCount(lineStart, lineEnd)) return null
        return source.offsetByCodePoints(lineStart, column - 1)
    }

    /** The unique string literal directly followed by `(`; method names are the only such literals. */
    private fun nameToken(header: String): Token? {
        val tokens = significantTokens(header) ?: return null
        val candidates = tokens.zipWithNext().filter { (literal, next) ->
            literal.type == GroovyLangLexer.StringLiteral && next.type == GroovyLangLexer.LPAREN
        }
        return candidates.singleOrNull()?.first
    }

    /** Same resilience rule as `GroovyTokenIndex`: lexer failures are unavailable, real errors propagate. */
    private fun significantTokens(text: String): List<Token>? = runCatching {
        val lexer = GroovyLangLexer(CharStreams.fromString(text))
        generateSequence { lexer.nextToken() }
            .takeWhile { it.type != Token.EOF }
            .filter { it.channel == Token.DEFAULT_CHANNEL && it.type != GroovyLangLexer.NL }
            .toList()
    }.onFailure { failure ->
        if (failure is Error && failure !is GroovySyntaxError) throw failure
    }.getOrNull()

    /** Decodes a raw literal into its value and an offset map; null for unsupported spellings. */
    private fun decode(raw: String, base: Int): Pair<String, UnrollLiteral>? {
        val delimiter = listOf("'''", "\"\"\"", "'", "\"", "/").firstOrNull { raw.startsWith(it) } ?: return null
        if (raw.length < 2 * delimiter.length || !raw.endsWith(delimiter)) return null
        val bodyStart = delimiter.length
        val bodyEnd = raw.length - delimiter.length
        val decoder = LiteralDecoder(raw, bodyStart, bodyEnd, base, slashy = delimiter == "/")
        val value = decoder.run() ?: return null
        return value to UnrollLiteral(base until base + raw.length, value, decoder.offsets())
    }

    private class LiteralDecoder(
        private val raw: String,
        private var index: Int,
        private val end: Int,
        private val base: Int,
        private val slashy: Boolean,
    ) {
        private val value = StringBuilder()
        private val positions = ArrayList<Int>()

        fun offsets(): IntArray = (positions + (base + end)).toIntArray()

        fun run(): String? {
            while (index < end) {
                val consumed = if (raw[index] == '\\') escape() else plain()
                if (!consumed) return null
            }
            return value.toString()
        }

        private fun emit(char: Char, at: Int) {
            value.append(char)
            positions.add(base + at)
        }

        private fun plain(): Boolean {
            val at = index
            if (!slashy && isCrlf(at)) {
                emit('\n', at)
                index += 2
            } else {
                emit(raw[at], at)
                index++
            }
            return true
        }

        private fun isCrlf(at: Int): Boolean = raw[at] == '\r' && at + 1 < end && raw[at + 1] == '\n'

        private fun escape(): Boolean {
            val at = index
            if (at + 1 >= end) return false
            val next = raw[at + 1]
            val mapped = simple[next]
            return when {
                slashy && next == '/' -> emitEscape('/', at)
                slashy -> plain()
                mapped != null -> emitEscape(mapped, at)
                next == '\n' -> skip(2)
                next == '\r' -> at + 2 < end && raw[at + 2] == '\n' && skip(CRLF_CONTINUATION)
                next == 'u' -> unicode(at)
                next in '0'..'7' -> octal(at)
                else -> false
            }
        }

        private fun emitEscape(char: Char, at: Int): Boolean {
            emit(char, at)
            index += 2
            return true
        }

        /** A line continuation contributes no characters to the value. */
        private fun skip(length: Int): Boolean {
            index += length
            return true
        }

        private fun unicode(at: Int): Boolean {
            val cursor = at + 2
            if (cursor + HEX_DIGITS > end) return false
            val code = raw.substring(cursor, cursor + HEX_DIGITS).toIntOrNull(HEX_RADIX) ?: return false
            emit(code.toChar(), at)
            index = cursor + HEX_DIGITS
            return true
        }

        /** Java octal escapes: `\d`, `\dd` or `\[0-3]dd` with octal digits. */
        private fun octal(at: Int): Boolean {
            val maxDigits = if (raw[at + 1] <= '3') LONG_OCTAL else SHORT_OCTAL
            var cursor = at + 1
            while (cursor < end && cursor - at - 1 < maxDigits && raw[cursor] in '0'..'7') cursor++
            emit(raw.substring(at + 1, cursor).toInt(OCTAL_RADIX).toChar(), at)
            index = cursor
            return true
        }

        companion object {
            private const val HEX_DIGITS = 4
            private const val CRLF_CONTINUATION = 3
            private const val LONG_OCTAL = 3
            private const val SHORT_OCTAL = 2
            private const val HEX_RADIX = 16
            private const val OCTAL_RADIX = 8
            private val simple = mapOf(
                'b' to '\b', 't' to '\t', 'n' to '\n', 'f' to '\u000C', 'r' to '\r', 's' to ' ',
                '"' to '"', '\'' to '\'', '\\' to '\\', '$' to '$',
            )
        }
    }
}
