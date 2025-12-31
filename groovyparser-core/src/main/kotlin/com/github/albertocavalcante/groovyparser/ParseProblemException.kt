package com.github.albertocavalcante.groovyparser

/**
 * Exception thrown when parsing fails due to syntax errors.
 * Contains the list of problems encountered during parsing.
 */
class ParseProblemException(val problems: List<Problem>) : RuntimeException(buildMessage(problems)) {

    companion object {
        private fun buildMessage(problems: List<Problem>): String {
            if (problems.isEmpty()) {
                return "Parsing failed with unknown error"
            }
            return problems.joinToString("\n") { it.toString() }
        }
    }
}
