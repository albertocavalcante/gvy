package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovyLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapExpression
import org.eclipse.lsp4j.Position

/** Lexes captured source so comments and literal text cannot create keyword tokens. */
internal object GroovySemanticKeywords {
    fun append(
        input: SemanticTokenInput,
        tokens: MutableList<GroovySemanticTokenProvider.SemanticToken>,
        checkCancelled: () -> Unit,
    ) {
        val index = input.sourceRanges?.index ?: return
        checkCancelled()
        val lexer = GroovyLangLexer(CharStreams.fromString(index.text))
        lexer.removeErrorListeners()
        val occupied = occupiedPositions(input, tokens, checkCancelled)
        var previous = Token.INVALID_TYPE
        try {
            while (true) {
                checkCancelled()
                val token = lexer.nextToken()
                if (token.type == Token.EOF || startsBlockComment(input, token)) break
                if (token.channel == Token.DEFAULT_CHANNEL && token.type != GroovyLexer.NL) {
                    keyword(input, token, previous)?.takeUnless {
                        (it.line to it.startChar) in occupied
                    }?.let(tokens::add)
                    previous = token.type
                }
            }
        } catch (_: GroovySyntaxError) {
            // Keep proven tokens before malformed input; cancellation and fatal errors propagate.
        }
    }

    private fun occupiedPositions(
        input: SemanticTokenInput,
        tokens: List<GroovySemanticTokenProvider.SemanticToken>,
        checkCancelled: () -> Unit,
    ): Set<Pair<Int, Int>> {
        val occupied = tokens.mapTo(mutableSetOf()) { it.line to it.startChar }
        input.astModel.getNodes(input.uri).forEach { node ->
            checkCancelled()
            // Entries are untracked containers; their map and constant keys carry URI ownership.
            if (node is MapExpression && input.astModel.getUri(node) == input.uri) {
                node.mapEntryExpressions.forEach { entry ->
                    checkCancelled()
                    keyPosition(input, entry.keyExpression)?.let(occupied::add)
                }
            }
        }
        return occupied
    }

    private fun keyPosition(input: SemanticTokenInput, key: Expression): Pair<Int, Int>? {
        if (key !is ConstantExpression || key.value !is String) return null
        val source = input.sourceRanges ?: return null
        val range = source.range(key) ?: return null
        val start = source.index.offsetOf(range.start)
        val end = source.index.offsetOf(range.end)
        return (range.start.line to range.start.character).takeIf {
            source.index.text.substring(start, end) == key.value
        }
    }

    private fun startsBlockComment(input: SemanticTokenInput, token: Token): Boolean {
        if (token.type != GroovyLexer.DIV) return false
        val index = input.sourceRanges?.index ?: return false
        val position = index.utf16Position(Position(token.line - 1, token.charPositionInLine)) ?: return false
        // A complete block comment is normally one NL token. Recovery may instead expose its slash.
        return index.text.startsWith("/*", index.offsetOf(position))
    }

    private fun isKeyword(type: Int, previous: Int): Boolean =
        type in GroovyLexer.AS..GroovyLexer.WHILE && (previous !in memberOperators || type == GroovyLexer.CLASS)

    private fun keyword(
        input: SemanticTokenInput,
        token: Token,
        previous: Int,
    ): GroovySemanticTokenProvider.SemanticToken? {
        if (!isKeyword(token.type, previous)) return null
        val index = input.sourceRanges?.index ?: return null
        val text = token.text ?: return null
        val position = index.utf16Position(Position(token.line - 1, token.charPositionInLine)) ?: return null
        val start = index.offsetOf(position)
        if (text.isEmpty() || text.length > index.text.length - start ||
            !index.text.regionMatches(start, text, 0, text.length)
        ) {
            return null
        }
        val end = index.positionOf(start + text.length)
        if (end.line != position.line) return null
        val type = if (token.type in modifiers) {
            GroovySemanticTokenProvider.TokenTypes.MODIFIER
        } else {
            GroovySemanticTokenProvider.TokenTypes.KEYWORD
        }
        return GroovySemanticTokenProvider.SemanticToken(position.line, position.character, text.length, type, 0)
    }

    private val memberOperators = setOf(
        GroovyLexer.DOT,
        GroovyLexer.SAFE_DOT,
        GroovyLexer.SPREAD_DOT,
        GroovyLexer.SAFE_CHAIN_DOT,
        GroovyLexer.METHOD_POINTER,
        GroovyLexer.METHOD_REFERENCE,
    )
    private val modifiers = setOf(
        GroovyLexer.ABSTRACT, GroovyLexer.FINAL, GroovyLexer.NATIVE, GroovyLexer.NON_SEALED,
        GroovyLexer.PRIVATE, GroovyLexer.PROTECTED, GroovyLexer.PUBLIC, GroovyLexer.SEALED,
        GroovyLexer.STATIC, GroovyLexer.STRICTFP, GroovyLexer.SYNCHRONIZED, GroovyLexer.THREADSAFE,
        GroovyLexer.TRANSIENT, GroovyLexer.VOLATILE,
    )
}
