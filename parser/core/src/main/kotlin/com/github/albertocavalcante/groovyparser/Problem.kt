package com.github.albertocavalcante.groovyparser

/**
 * A problem encountered during parsing, such as a syntax error or warning.
 */
data class Problem(
    val message: String,
    val position: Position? = null,
    val range: Range? = null,
    val severity: ProblemSeverity = ProblemSeverity.ERROR,
    val cause: Throwable? = null,
) {
    /**
     * Creates a problem with a message and position.
     */
    constructor(message: String, position: Position) : this(
        message = message,
        position = position,
        range = null,
        severity = ProblemSeverity.ERROR,
        cause = null,
    )

    /**
     * Creates a problem with a message and range.
     */
    constructor(message: String, range: Range) : this(
        message = message,
        position = range.begin,
        range = range,
        severity = ProblemSeverity.ERROR,
        cause = null,
    )

    /**
     * Creates a problem with a message, position, and severity.
     */
    constructor(message: String, position: Position?, severity: ProblemSeverity) : this(
        message = message,
        position = position,
        range = null,
        severity = severity,
        cause = null,
    )

    /**
     * Creates a problem with a message, range, and severity.
     */
    constructor(message: String, range: Range, severity: ProblemSeverity) : this(
        message = message,
        position = range.begin,
        range = range,
        severity = severity,
        cause = null,
    )

    /**
     * Returns true if this is an error.
     */
    val isError: Boolean get() = severity == ProblemSeverity.ERROR

    /**
     * Returns true if this is a warning.
     */
    val isWarning: Boolean get() = severity == ProblemSeverity.WARNING

    override fun toString(): String {
        val locationStr = when {
            range != null -> " at $range"
            position != null -> " at $position"
            else -> ""
        }
        val severityStr = when (severity) {
            ProblemSeverity.ERROR -> "Error"
            ProblemSeverity.WARNING -> "Warning"
            ProblemSeverity.INFO -> "Info"
            ProblemSeverity.HINT -> "Hint"
        }
        return "$severityStr: $message$locationStr"
    }

    companion object {
        /**
         * Comparator that orders problems by their begin position.
         */
        val COMPARATOR_BY_BEGIN_POSITION: Comparator<Problem> = Comparator { p1, p2 ->
            val pos1 = p1.position ?: Position.HOME
            val pos2 = p2.position ?: Position.HOME
            pos1.compareTo(pos2)
        }

        /**
         * Comparator that orders problems by severity (errors first) then position.
         */
        val COMPARATOR_BY_SEVERITY: Comparator<Problem> = Comparator { p1, p2 ->
            val severityCompare = p1.severity.compareTo(p2.severity)
            if (severityCompare != 0) {
                severityCompare
            } else {
                COMPARATOR_BY_BEGIN_POSITION.compare(p1, p2)
            }
        }

        /**
         * Creates an error problem.
         */
        fun error(message: String, position: Position? = null, range: Range? = null): Problem =
            Problem(message, position, range, ProblemSeverity.ERROR, null)

        /**
         * Creates a warning problem.
         */
        fun warning(message: String, position: Position? = null, range: Range? = null): Problem =
            Problem(message, position, range, ProblemSeverity.WARNING, null)

        /**
         * Creates an info problem.
         */
        fun info(message: String, position: Position? = null, range: Range? = null): Problem =
            Problem(message, position, range, ProblemSeverity.INFO, null)
    }
}
