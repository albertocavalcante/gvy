package com.github.albertocavalcante.groovyparser.api

/**
 * Represents a 0-based position within a document.
 */
data class ParserPosition(val line: Int, val character: Int)

/**
 * Represents a range in a document using 0-based positions.
 */
data class ParserRange(val start: ParserPosition, val end: ParserPosition) {
    companion object {
        fun point(line: Int, character: Int): ParserRange {
            val position = ParserPosition(line, character)
            return ParserRange(position, position)
        }

        fun singleLine(line: Int, startCharacter: Int, endCharacter: Int): ParserRange =
            ParserRange(ParserPosition(line, startCharacter), ParserPosition(line, endCharacter))
    }
}

/**
 * Severity levels supported by the parser diagnostics.
 */
enum class ParserSeverity {
    ERROR,
    WARNING,
    INFORMATION,
    HINT,
}

/**
 * Diagnostic emitted by the parser without any LSP dependency.
 */
data class ParserDiagnostic(
    val range: ParserRange = ParserRange.point(0, 0),
    val severity: ParserSeverity = ParserSeverity.ERROR,
    val message: String,
    val source: String = "groovy-parser",
    val code: String? = null,
)
