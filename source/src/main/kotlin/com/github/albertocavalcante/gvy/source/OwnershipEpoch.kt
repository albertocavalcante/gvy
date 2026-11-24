// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A count of the retirements of every ticket of one ownership fence. A ticket issued in an older epoch is
 * stale whatever its file did since.
 *
 * It is not a [DocumentVersion] or an [AnalysisGeneration]; the three do not compare or convert into each
 * other. Epochs only move forward, by [next].
 */
@JvmInline
public value class OwnershipEpoch private constructor(private val count: Long) : Comparable<OwnershipEpoch> {
    /** The epoch after this one; throws [ArithmeticException] on overflow instead of wrapping. */
    public fun next(): OwnershipEpoch = OwnershipEpoch(Math.incrementExact(count))

    /** Orders epochs by age. */
    override fun compareTo(other: OwnershipEpoch): Int = count.compareTo(other.count)

    /** Shows the count with its kind, so a log line cannot be misread. */
    override fun toString(): String = "OwnershipEpoch($count)"

    /** The first epoch. */
    public companion object {
        /** The epoch before any retirement. */
        public val INITIAL: OwnershipEpoch = OwnershipEpoch(0L)
    }
}
