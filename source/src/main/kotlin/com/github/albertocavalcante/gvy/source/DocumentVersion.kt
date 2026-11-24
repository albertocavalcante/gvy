// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * The version a client gave an open document, as sent in `textDocument/didOpen` and `didChange`.
 *
 * Versions grow within one open/close lifecycle of a document and may restart in the next, so an order
 * between two versions means something only for the same lifecycle. It is not a server-side generation or
 * an ownership epoch: those counters are planned as separate types (#2394) and will not compare or convert
 * into this one.
 * The raw number enters and leaves only at the protocol boundary ([of], [value]); the protocol allows any
 * [Int], so there is no range check.
 */
@JvmInline
public value class DocumentVersion private constructor(
    /** The number as the client sent it. Read it only to send it back to the client or to log it. */
    public val value: Int,
) : Comparable<DocumentVersion> {
    /** Orders versions by the client's numbering. */
    override fun compareTo(other: DocumentVersion): Int = value.compareTo(other.value)

    /** Shows the number with its kind, so a log line cannot be misread. */
    override fun toString(): String = "DocumentVersion($value)"

    /** Factories that name where the [Int] came from. */
    public companion object {
        /** The version a client sent as [value]. */
        public fun of(value: Int): DocumentVersion = DocumentVersion(value)

        /** The version a client sent as [value], or null when the client sent none. */
        public fun ofOrNull(value: Int?): DocumentVersion? = value?.let(::DocumentVersion)
    }
}
