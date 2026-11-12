// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/** A zero-based line and UTF-16 code-unit character offset. */
public data class Utf16Position(val line: Int, val character: Int) {
    init {
        require(line >= 0) { "line must be nonnegative" }
        require(character >= 0) { "character must be nonnegative" }
    }
}

/**
 * An ordered half-open range; equal endpoints describe an empty range. Ordering does not establish
 * that the positions are in bounds for any text.
 */
public data class Utf16Range(val start: Utf16Position, val endExclusive: Utf16Position) {
    init {
        require(
            start.line < endExclusive.line ||
                (start.line == endExclusive.line && start.character <= endExclusive.character),
        ) { "range end must not precede start" }
    }
}

/**
 * A source identifier as supplied by the caller, with an optional range. This value does not validate
 * the identifier or assert that the range describes current source text. Hosts and codecs own
 * identifier validation and format-specific URI projection.
 */
public data class SourceLocation(val uri: String, val range: Utf16Range?)
