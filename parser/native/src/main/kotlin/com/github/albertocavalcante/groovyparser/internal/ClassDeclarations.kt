package com.github.albertocavalcante.groovyparser.internal

import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovyLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError

private val PROPERTY_OPERATORS = setOf(
    GroovyLexer.DOT,
    GroovyLexer.SAFE_DOT,
    GroovyLexer.SAFE_CHAIN_DOT,
    GroovyLexer.SPREAD_DOT,
    GroovyLexer.METHOD_POINTER,
    GroovyLexer.METHOD_REFERENCE,
    GroovyLexer.AT, // Direct field access: object.@class.
)
private val CLASS_NAMES = setOf(GroovyLexer.Identifier, GroovyLexer.CapitalizedIdentifier)

/** Distinguishes real declarations from class literals and text in comments or strings. */
internal fun hasClassDeclaration(source: String, checkCancelled: () -> Unit): Boolean {
    val lexer = GroovyLangLexer(CharStreams.fromString(source))
    lexer.removeErrorListeners()
    var previous = Token.INVALID_TYPE
    var declarationNameExpected = false
    try {
        while (true) {
            checkCancelled()
            val token = lexer.nextToken()
            when {
                token.type == Token.EOF -> return false
                token.channel != Token.DEFAULT_CHANNEL || token.type == GroovyLexer.NL -> continue
                declarationNameExpected && token.type in CLASS_NAMES -> return true
            }
            declarationNameExpected = token.type == GroovyLexer.CLASS && previous !in PROPERTY_OPERATORS
            previous = token.type
        }
    } catch (_: GroovySyntaxError) {
        // Malformed editor input is not evidence of a declaration. Cancellation and fatal errors propagate.
        return false
    }
}
