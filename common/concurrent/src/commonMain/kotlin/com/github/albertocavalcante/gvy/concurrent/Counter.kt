// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package com.github.albertocavalcante.gvy.concurrent

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** A count shared between threads, such as calls made or attempts started. */
class Counter(initial: Long = 0) {
    private val count = AtomicLong(initial)

    /** The current count. */
    val value: Long
        get() = count.load()

    /** Adds one and returns the new count. */
    fun increment(): Long = count.incrementAndFetch()
}
