// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package com.github.albertocavalcante.gvy.concurrent

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A flag that is set at most once and never cleared, safe to share between threads.
 *
 * Use it for a one-way transition such as close or shutdown. Exactly one call of [trySet] returns `true`, so work
 * guarded by it runs once even when several threads race to do it:
 *
 * ```
 * override fun close() {
 *     if (!closed.trySet()) return
 *     releaseResources()
 * }
 * ```
 */
class OnceFlag {
    private val flag = AtomicBoolean(false)

    /** Whether the flag has been set. */
    val isSet: Boolean
        get() = flag.load()

    /** Sets the flag. Returns `true` only for the call that set it; every later call returns `false`. */
    fun trySet(): Boolean = flag.compareAndSet(expectedValue = false, newValue = true)
}
