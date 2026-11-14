// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex.DiagnosticToken
import com.github.albertocavalcante.groovyparser.tokens.TokenContext
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.codehaus.groovy.syntax.SyntaxException

/** Recognizes a missing operand only at the compiler's rejected token or its immediate boundary. */
internal fun expressionDiagnostic(
    error: SyntaxException,
    source: SourceSnapshot,
    index: GroovyTokenIndex,
): ParserDiagnostic? {
    if (!index.complete || !error.originalMessage.startsWith("Unexpected input:")) return null
    val offset = source.compilerOffsetOrNull(error.line, error.startColumn) ?: return null
    val tokens = index.diagnosticTokens.filter {
        it.channel == 0 && it.symbolicName != "NL" &&
            it.context != TokenContext.LineComment && it.context != TokenContext.BlockComment
    }
    // Interpolation is an independent expression scope. Keep its original error until that scope is modeled.
    if (insideInterpolation(index.diagnosticTokens, offset)) return null
    val atError = tokens.indexOfFirst { offset in it.start until it.end }
    val beforeError = tokens.indexOfLast { it.end <= offset }
    val candidates = listOf(beforeError, atError).filter { it >= 0 }.distinct()
    for (candidateIndex in candidates) {
        val diagnostic = candidateDiagnostic(candidateIndex, tokens, offset, source, index)
        if (diagnostic != null) return diagnostic
    }
    return null
}

private fun candidateDiagnostic(
    candidateIndex: Int,
    tokens: List<DiagnosticToken>,
    offset: Int,
    source: SourceSnapshot,
    index: GroovyTokenIndex,
): ParserDiagnostic? {
    val candidate = tokens[candidateIndex]
    val next = tokens.getOrNull(candidateIndex + 1)
    val matchesCandidate =
        candidate.context == TokenContext.Code &&
            !inImportOrPackage(candidate, index) &&
            (next == null || next.symbolicName in NAME_BOUNDARIES) &&
            matchesErrorPosition(candidate, next, offset, source, index)
    if (!matchesCandidate) return null

    val messageAndCode = messageFor(candidateIndex, tokens, source) ?: return null
    return ParserDiagnostic(
        range = source.range(candidate.start, candidate.end),
        severity = ParserSeverity.ERROR,
        message = messageAndCode.first,
        code = messageAndCode.second,
    )
}

private fun matchesErrorPosition(
    candidate: DiagnosticToken,
    next: DiagnosticToken?,
    offset: Int,
    source: SourceSnapshot,
    index: GroovyTokenIndex,
): Boolean {
    val trailingBoundary = next == null && (
        offset == source.text.length || index.diagnosticTokens.any {
            it.symbolicName == "NL" && offset in it.start until it.end
        }
        )
    return offset in candidate.start..candidate.end || trailingBoundary ||
        (next != null && offset in next.start until next.end)
}

private fun messageFor(
    candidateIndex: Int,
    tokens: List<DiagnosticToken>,
    source: SourceSnapshot,
): Pair<String, String>? {
    val candidate = tokens[candidateIndex]
    val next = tokens.getOrNull(candidateIndex + 1)
    val text = source.text.substring(candidate.start, candidate.end)
    return when {
        candidate.symbolicName in MEMBER_OPERATORS && hasReceiver(tokens, candidateIndex) ->
            "Incomplete member access: expected a member name after '$text'." to "incomplete-member-access"
        candidate.symbolicName in DECLARATIONS && startsDeclaration(tokens, candidateIndex, source) ->
            "Incomplete declaration: expected a name after '$text'." to "incomplete-declaration"
        candidate.symbolicName == "NEW" -> "Expected a type after 'new'." to "expected-type"
        candidate.symbolicName in EXPRESSION_OPERATORS && next?.symbolicName != "LBRACE" &&
            hasLeftOperand(tokens, candidateIndex) ->
            "Expected an expression after '$text'." to "expected-expression"
        else -> null
    }
}

private fun insideInterpolation(tokens: List<DiagnosticToken>, offset: Int): Boolean {
    var depth = 0
    for (token in tokens) {
        if (token.start > offset) break
        when (token.symbolicName) {
            "GStringBegin" -> depth++
            "GStringEnd" -> depth--
        }
    }
    return depth > 0
}

private fun inImportOrPackage(candidate: DiagnosticToken, index: GroovyTokenIndex): Boolean {
    val preceding = index.diagnosticTokens.takeWhile { it.start < candidate.start }
    val start = preceding.indexOfLast {
        it.channel == 0 && it.symbolicName in STATEMENT_BOUNDARIES
    } + 1
    return preceding.drop(start).any { it.symbolicName == "IMPORT" || it.symbolicName == "PACKAGE" }
}

private fun hasReceiver(tokens: List<DiagnosticToken>, candidateIndex: Int): Boolean {
    val previous = tokens.getOrNull(candidateIndex - 1) ?: return false
    return previous.context == TokenContext.StringLiteral || previous.symbolicName in RECEIVER_ENDINGS
}

private fun hasLeftOperand(tokens: List<DiagnosticToken>, candidateIndex: Int): Boolean {
    if (tokens[candidateIndex].symbolicName in setOf("ADD", "SUB")) return true
    val previous = tokens.getOrNull(candidateIndex - 1) ?: return false
    return hasReceiver(tokens, candidateIndex) || previous.symbolicName.endsWith("Literal")
}

private fun startsDeclaration(tokens: List<DiagnosticToken>, candidateIndex: Int, source: SourceSnapshot): Boolean {
    val previous = tokens.getOrNull(candidateIndex - 1) ?: return true
    return previous.symbolicName in DECLARATION_PREFIXES ||
        source.text.substring(previous.end, tokens[candidateIndex].start).contains('\n')
}

private val MEMBER_OPERATORS = setOf("DOT", "SAFE_DOT", "SPREAD_DOT")
private val DECLARATIONS = setOf("CLASS", "INTERFACE", "TRAIT", "ENUM")
private val EXPRESSION_OPERATORS = setOf("ASSIGN", "ADD", "SUB", "MUL", "DIV", "AND", "OR")
private val REJECTED_BOUNDARIES = setOf(
    "SEMI", "RPAREN", "RBRACK", "RBRACE", "DEF", "CLASS", "INTERFACE", "TRAIT", "ENUM",
)
private val NAME_BOUNDARIES = REJECTED_BOUNDARIES + "LBRACE"
private val STATEMENT_BOUNDARIES = setOf("NL", "SEMI", "LBRACE", "RBRACE")
private val RECEIVER_ENDINGS = setOf(
    "Identifier",
    "CapitalizedIdentifier",
    "THIS",
    "SUPER",
    "RPAREN",
    "RBRACK",
    "RBRACE",
)
private val DECLARATION_PREFIXES = setOf(
    "SEMI", "LBRACE", "RBRACE", "PUBLIC", "PROTECTED", "PRIVATE", "ABSTRACT", "FINAL", "STATIC", "STRICTFP",
)
