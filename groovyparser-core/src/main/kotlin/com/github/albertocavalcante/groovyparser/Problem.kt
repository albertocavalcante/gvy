package com.github.albertocavalcante.groovyparser

/**
 * A problem encountered during parsing, such as a syntax error or warning.
 */
data class Problem(
    val message: String,
    val position: Position? = null,
    val range: Range? = null,
    val cause: Throwable? = null,
) {
    /**
     * Creates a problem with a message and position.
     */
    constructor(message: String, position: Position) : this(
        message = message,
        position = position,
        range = null,
        cause = null,
    )

    /**
     * Creates a problem with a message and range.
     */
    constructor(message: String, range: Range) : this(
        message = message,
        position = range.begin,
        range = range,
        cause = null,
    )

    override fun toString(): String {
        val locationStr = when {
            range != null -> " at $range"
            position != null -> " at $position"
            else -> ""
        }
        return "Problem: $message$locationStr"
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
    }
}
