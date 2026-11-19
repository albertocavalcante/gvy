// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex.DiagnosticToken
import com.github.albertocavalcante.groovyparser.tokens.TokenContext
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.codehaus.groovy.syntax.SyntaxException

/** Refine only a compiler error at the end of the same incomplete declaration. */
internal fun importDiagnostic(
    error: SyntaxException,
    source: SourceSnapshot,
    index: GroovyTokenIndex,
): ParserDiagnostic? {
    val errorOffset = if (index.complete) source.compilerOffsetOrNull(error.line, error.startColumn) else null
    val statement = errorOffset?.let { declarationAt(it, source, index.diagnosticTokens) } ?: return null
    val message = declarationMessage(source, statement) ?: return null
    val last = statement.last()
    val first = source.text.substring(statement.first().start, statement.first().end)
    return ParserDiagnostic(
        range = source.range(last.start, last.end),
        severity = ParserSeverity.ERROR,
        message = message,
        source = "groovy-parser",
        code = if (first == "import") "incomplete-import" else "incomplete-package",
    )
}

private fun declarationAt(
    errorOffset: Int,
    source: SourceSnapshot,
    tokens: List<DiagnosticToken>,
): List<DiagnosticToken>? {
    if (insideGString(errorOffset, tokens)) return null
    fun isBoundary(token: DiagnosticToken): Boolean = token.channel == 0 &&
        (token.symbolicName == "NL" || source.text.substring(token.start, token.end) == ";")
    val boundary = tokens.indexOfFirst { it.start >= errorOffset && isBoundary(it) }
        .let { if (it < 0) tokens.size else it }
    val before = tokens.take(boundary)
    val previousBoundary = before.indexOfLast(::isBoundary)
    val statementTokens = before.drop(previousBoundary + 1)
    if (statementTokens.any { it.context == TokenContext.StringLiteral || it.context == TokenContext.GString }) {
        return null
    }
    val statement = statementTokens.filter { it.channel == 0 && it.context == TokenContext.Code }
    return statement.takeIf { it.isNotEmpty() && errorOffset >= it.last().start }
}

private fun insideGString(errorOffset: Int, tokens: List<DiagnosticToken>): Boolean {
    // Interpolation contains ordinary code tokens, including newlines and declaration-like text.
    var depth = 0
    for (token in tokens) {
        if (token.start > errorOffset) break
        when (token.symbolicName) {
            "GStringBegin" -> depth++
            "GStringEnd" -> depth--
        }
    }
    return depth != 0
}

private fun declarationMessage(source: SourceSnapshot, statement: List<DiagnosticToken>): String? {
    val words = statement.map { source.text.substring(it.start, it.end) }
    val first = words.first()
    val last = words.last()
    return when (first) {
        "import" -> importMessage(source, statement, words, last)
        "package" -> packageMessage(source, statement, words, last)
        else -> null
    }
}

private fun importMessage(
    source: SourceSnapshot,
    statement: List<DiagnosticToken>,
    words: List<String>,
    last: String,
): String? {
    if (words.size == 1 || words == listOf("import", "static")) {
        return "Incomplete import: expected an import name."
    }
    val nameParts = statement.drop(if (words.getOrNull(1) == "static") 2 else 1)
    val pathParts = if (last == "as") nameParts.dropLast(1) else nameParts
    if (!validPath(source, pathParts)) return null
    return when {
        last == "." -> "Incomplete import: expected a name or '*' after '.'."
        last == "as" && pathParts.size % 2 == 1 -> "Incomplete import: expected an alias after 'as'."
        else -> null
    }
}

private fun packageMessage(
    source: SourceSnapshot,
    statement: List<DiagnosticToken>,
    words: List<String>,
    last: String,
): String? = when {
    words.size == 1 -> "Incomplete package declaration: expected a package name."
    last == "." && validPath(source, statement.drop(1)) ->
        "Incomplete package declaration: expected a name after '.'."
    else -> null
}

private fun validPath(source: SourceSnapshot, tokens: List<DiagnosticToken>): Boolean =
    tokens.withIndex().all { (index, token) ->
        if (index % 2 == 0) {
            token.symbolicName in setOf("Identifier", "CapitalizedIdentifier")
        } else {
            source.text.substring(token.start, token.end) == "."
        }
    }
