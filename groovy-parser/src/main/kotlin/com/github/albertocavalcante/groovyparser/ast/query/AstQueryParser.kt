package com.github.albertocavalcante.groovyparser.ast.query

class AstQueryParser(private val raw: String) {
    private var index = 0

    fun parse(): AstQuery {
        skipWhitespace()
        if (isEof()) {
            throw IllegalArgumentException("Query is empty")
        }
        val patterns = mutableListOf<AstQueryPattern>()
        while (!isEof()) {
            patterns.add(parsePattern())
            skipWhitespace()
        }
        return AstQuery(patterns)
    }

    private fun parsePattern(): AstQueryPattern {
        expect('(')
        skipWhitespace()
        val type = parseIdentifier()
        var capture: String? = null
        val children = mutableListOf<AstQueryPattern>()

        while (true) {
            skipWhitespace()
            when (peek()) {
                ')' -> {
                    index++
                    break
                }
                '@' -> {
                    if (capture != null) {
                        throw error("Duplicate capture")
                    }
                    capture = parseCapture()
                }
                '(' -> children.add(parsePattern())
                else -> throw error("Expected capture or child pattern")
            }
        }

        return AstQueryPattern(type, capture, children)
    }

    private fun parseCapture(): String {
        expect('@')
        return parseIdentifier()
    }

    private fun parseIdentifier(): String {
        if (isEof()) throw error("Expected identifier")
        val start = index
        val first = raw[index]
        if (!first.isLetter() && first != '_') {
            throw error("Expected identifier")
        }
        index++
        while (!isEof()) {
            val ch = raw[index]
            if (ch.isLetterOrDigit() || ch == '_') {
                index++
            } else {
                break
            }
        }
        return raw.substring(start, index)
    }

    private fun skipWhitespace() {
        while (!isEof() && raw[index].isWhitespace()) {
            index++
        }
    }

    private fun expect(ch: Char) {
        if (isEof() || raw[index] != ch) {
            throw error("Expected '$ch'")
        }
        index++
    }

    private fun peek(): Char? = if (isEof()) null else raw[index]

    private fun isEof(): Boolean = index >= raw.length

    private fun error(message: String): IllegalArgumentException =
        IllegalArgumentException("Query parse error at $index: $message")
}
