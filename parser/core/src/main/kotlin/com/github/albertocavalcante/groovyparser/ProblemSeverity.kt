package com.github.albertocavalcante.groovyparser

/**
 * Severity level for parsing problems.
 *
 * Matches common LSP diagnostic severity levels.
 */
enum class ProblemSeverity {
    /**
     * Fatal error that prevents parsing from completing.
     * E.g., syntax errors, unclosed braces.
     */
    ERROR,

    /**
     * Non-fatal issue that should be addressed.
     * E.g., deprecated syntax, unused imports.
     */
    WARNING,

    /**
     * Informational message.
     * E.g., suggestions, style hints.
     */
    INFO,

    /**
     * Hint or suggestion.
     * E.g., code style recommendations.
     */
    HINT,

    ;

    /**
     * Returns true if this severity is at least as severe as the given severity.
     */
    fun isAtLeast(other: ProblemSeverity): Boolean = this.ordinal <= other.ordinal

    companion object {
        /**
         * Comparator that orders by severity (ERROR first, HINT last).
         */
        val COMPARATOR: Comparator<ProblemSeverity> = Comparator { s1, s2 ->
            s1.ordinal.compareTo(s2.ordinal)
        }
    }
}
