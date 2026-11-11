package com.github.albertocavalcante.gvy.fmt

/** What a formatting request produced. [text] is always the text to keep. */
sealed interface FormatOutcome {
    val text: String

    /** Formatting changed nothing, or the formatter could not handle the input. */
    data class Unchanged(override val text: String) : FormatOutcome

    /** Formatting changed only layout; [text] is the new document. */
    data class Formatted(override val text: String) : FormatOutcome

    /**
     * Formatting would have changed more than layout, so the result was dropped and [text] is
     * the original. [reason] is a one-line description for logs, [cause] set when the check
     * itself failed.
     */
    data class Refused(override val text: String, val reason: String, val cause: Throwable? = null) : FormatOutcome
}
