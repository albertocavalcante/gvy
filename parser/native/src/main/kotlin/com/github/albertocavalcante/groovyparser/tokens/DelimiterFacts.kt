// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.tokens

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex.DiagnosticToken

/** Delimiter relationships collected from the existing lexer pass, excluding literal/interpolation islands. */
internal class DelimiterFacts {
    data class Conflict(val opener: DiagnosticToken, val closer: DiagnosticToken)

    private val openers = ArrayDeque<DiagnosticToken>()
    private val conflicts = mutableListOf<Conflict>()
    private var interpolationDepth = 0

    fun accept(token: DiagnosticToken) {
        when (token.symbolicName) {
            "GStringBegin" -> {
                interpolationDepth++
                return
            }
            "GStringEnd" -> {
                interpolationDepth--
                return
            }
        }
        if (interpolationDepth > 0 || token.context != TokenContext.Code || token.channel != 0) return

        when (token.symbolicName) {
            "LPAREN", "LBRACK", "LBRACE" -> openers.addLast(token)
            "RPAREN", "RBRACK", "RBRACE" -> acceptCloser(token)
        }
    }

    private fun acceptCloser(token: DiagnosticToken) {
        val opener = openers.lastOrNull() ?: return
        if (closingKind(opener) == token.symbolicName) {
            openers.removeLast()
        } else {
            conflicts.add(Conflict(opener, token))
        }
    }

    fun conflictAt(offset: Int): Conflict? = conflicts.firstOrNull {
        offset in it.closer.start until it.closer.end
    }

    fun unclosedAt(offset: Int, atEndOfFile: Boolean): DiagnosticToken? {
        // A contradictory closer requires an error at that closer, not a guessed unclosed error at EOF.
        if (conflicts.isNotEmpty()) return null
        return openers.firstOrNull { offset in it.start until it.end } ?: openers.lastOrNull().takeIf { atEndOfFile }
    }

    companion object {
        private fun closingKind(opener: DiagnosticToken): String = when (opener.symbolicName) {
            "LPAREN" -> "RPAREN"
            "LBRACK" -> "RBRACK"
            else -> "RBRACE"
        }

        fun closingText(opener: DiagnosticToken): String = when (opener.symbolicName) {
            "LPAREN" -> ")"
            "LBRACK" -> "]"
            else -> "}"
        }
    }
}
