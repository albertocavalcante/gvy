// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

/** What a formatting request produced. [text] is always the text to keep. */
sealed interface FormatOutcome {
    val text: String

    /** Formatting completed successfully without changing the input. */
    data class Unchanged(override val text: String) : FormatOutcome

    /** Formatting changed only layout; [text] is the new document. */
    data class Formatted(override val text: String) : FormatOutcome

    /**
     * Formatting would have changed more than layout, so the result was dropped and [text] is
     * the original. Recognized source parse and roundtrip failures are also refused. [reason] describes
     * the refusal; [cause] can identify an expected source refusal. Unknown engine failures propagate.
     */
    data class Refused(override val text: String, val reason: String, val cause: Throwable? = null) : FormatOutcome
}
