// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.findings

import com.github.albertocavalcante.gvy.source.SourceLocation

/** A rule code retains whether the producer supplied text or an integer. */
public sealed interface FindingCode {
    public data class Text(val value: String) : FindingCode
    public data class Number(val value: Int) : FindingCode
}

/** The severity reported by a producer. */
public enum class FindingSeverity {
    ERROR,
    WARNING,
    INFORMATION,
    HINT,
}

/**
 * One observation; null fields mean that the corresponding information is unavailable.
 * Observations do not establish whether analysis was complete or the source is current.
 */
public data class Finding(
    val location: SourceLocation?,
    val message: String,
    val severity: FindingSeverity?,
    val source: String?,
    val code: FindingCode?,
)
