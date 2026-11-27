// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package com.github.albertocavalcante.gvy.concurrent

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** A count shared between threads, such as calls made or attempts started. It starts at zero. */
class Counter {
    private val count = AtomicLong(0L)

    /** The current count. */
    val value: Long
        get() = count.load()

    /**
     * Adds one and returns the new count. Each call returns a different value, so the result can serve as a ticket or
     * an ID.
     */
    fun increment(): Long {
        // PROBE: deliberately broken; another increment can land between these two steps.
        count.incrementAndFetch()
        return count.load()
    }
}
