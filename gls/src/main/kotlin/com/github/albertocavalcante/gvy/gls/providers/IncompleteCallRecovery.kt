// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.eclipse.lsp4j.Position

/** Creates a parse-only copy for a method call whose opening parenthesis is at the caret. */
internal object IncompleteCallRecovery {
    private const val PLACEHOLDER = "null"
    private val incompleteCallSuffix = Regex("([\\p{L}_$][\\p{L}\\p{N}_$]*)[ \\t]*\\([ \\t]*$")

    private data class CallAtCaret(
        val offset: Int,
        val methodStart: Int,
        val openParen: Int,
        val hasCallCloserAfterCursor: Boolean,
    )

    fun parsingCopy(source: String, position: Position, tokenIndex: GroovyTokenIndex?): String? {
        val call = findCallAtCaret(source, position) ?: return null
        val tokens = tokenIndex ?: GroovyTokenIndex.build(source)
        if (!hasSafeCallTokens(call, tokens)) return null
        val closers = missingClosers(source, call, tokens) ?: return null
        return insertPlaceholder(source, call, closers)
    }

    private fun findCallAtCaret(source: String, position: Position): CallAtCaret? {
        val offset = offsetAt(source, position) ?: return null
        val prefix = source.substring(0, offset)
        val methodStart = methodStart(prefix, source) ?: return null
        val openParen = prefix.lastIndexOf('(')
        val hasCallCloser = hasOnlyClosersAfterCall(source.substring(offset))
        return hasCallCloser?.let { CallAtCaret(offset, methodStart, openParen, it) }
    }

    private fun offsetAt(source: String, position: Position): Int? {
        val index = TextIndex(source)
        return position.takeIf { index.isValidPosition(it) }?.let { index.offsetOf(it) }
    }

    private fun methodStart(prefix: String, source: String): Int? {
        val start = incompleteCallSuffix.find(prefix)?.groups?.get(1)?.range?.first ?: return null
        val hasIdentifierPrefix = start > 0 && Character.isJavaIdentifierPart(source.codePointBefore(start))
        val isConstructor = prefix.substring(0, start).trimEnd().endsWith("new")
        return start.takeUnless { hasIdentifierPrefix || isConstructor }
    }

    private fun hasOnlyClosersAfterCall(source: String): Boolean? {
        val first = source.indexOfFirst { !it.isWhitespace() }
        val hasCallCloser = first >= 0 && source[first] == ')'
        val remainderStart = (first + if (hasCallCloser) 1 else 0).coerceAtLeast(0)
        val tailIsSafe = source.drop(remainderStart).all { it.isWhitespace() || it in ")]}" }
        return hasCallCloser.takeIf { tailIsSafe }
    }

    private fun hasSafeCallTokens(call: CallAtCaret, tokens: GroovyTokenIndex): Boolean {
        if (!tokens.hasCompleteLexicalCoverage) return false
        return tokens.isCodeAt(call.methodStart) && tokens.isCodeAt(call.openParen)
    }

    private fun missingClosers(source: String, call: CallAtCaret, tokens: GroovyTokenIndex): String? {
        val delimiters = ArrayDeque<Char>()
        val validPrefix = scan(source, 0, call.offset, tokens, delimiters)
        val validCall = validPrefix && (call.hasCallCloserAfterCursor || closeDelimiter(')', delimiters))
        val validSuffix = validCall && scan(source, call.offset, source.length, tokens, delimiters)
        return if (validSuffix) delimiters.reversed().joinToString("") { matchingCloser(it).toString() } else null
    }

    private fun scan(
        source: String,
        start: Int,
        end: Int,
        tokens: GroovyTokenIndex,
        delimiters: ArrayDeque<Char>,
    ): Boolean {
        for (offset in start until end) {
            if (!trackDelimiter(source[offset], offset, tokens, delimiters)) return false
        }
        return true
    }

    private fun trackDelimiter(
        character: Char,
        offset: Int,
        tokens: GroovyTokenIndex,
        delimiters: ArrayDeque<Char>,
    ): Boolean {
        if (tokens.isInCommentOrString(offset)) return true
        return when (character) {
            '(', '[', '{' -> delimiters.addLast(character).let { true }
            ')', ']', '}' -> closeDelimiter(character, delimiters)
            else -> true
        }
    }

    private fun closeDelimiter(closer: Char, delimiters: ArrayDeque<Char>): Boolean {
        val expected = when (closer) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> return false
        }
        if (delimiters.lastOrNull() != expected) return false
        delimiters.removeLast()
        return true
    }

    private fun matchingCloser(opening: Char): Char = when (opening) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        else -> error("Unexpected opening delimiter: $opening")
    }

    private fun insertPlaceholder(source: String, call: CallAtCaret, closers: String): String =
        buildString(source.length + PLACEHOLDER.length + 1) {
            append(source, 0, call.offset)
            append(PLACEHOLDER)
            if (!call.hasCallCloserAfterCursor) append(')')
            append(source, call.offset, source.length)
            append(closers)
        }
}
