// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A count of the server-side invalidations of derived analysis state, such as a configuration change or a
 * retirement of every publication. A result carries the generation it began in and is stale once the
 * current one differs.
 *
 * It is not a [DocumentVersion] (the client's number for a buffer) or an [OwnershipEpoch]; the three do
 * not compare or convert into each other. Generations only move forward, by [next].
 */
@JvmInline
public value class AnalysisGeneration private constructor(private val count: Long) : Comparable<AnalysisGeneration> {
    /** The generation after this one; throws [ArithmeticException] on overflow instead of wrapping. */
    public fun next(): AnalysisGeneration = AnalysisGeneration(Math.incrementExact(count))

    /** The count, for the wire or a log line. Read it only there. */
    public fun toLong(): Long = count

    /** Orders generations by age. */
    override fun compareTo(other: AnalysisGeneration): Int = count.compareTo(other.count)

    /** Shows the count with its kind, so a log line cannot be misread. */
    override fun toString(): String = "AnalysisGeneration($count)"

    /** The first generation. */
    public companion object {
        /** The generation before any invalidation. */
        public val INITIAL: AnalysisGeneration = AnalysisGeneration(0L)
    }
}
