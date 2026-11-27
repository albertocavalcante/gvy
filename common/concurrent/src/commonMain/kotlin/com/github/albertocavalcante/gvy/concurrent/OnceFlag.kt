// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package com.github.albertocavalcante.gvy.concurrent

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A flag that is set at most once and never cleared, safe to share between threads.
 *
 * Use it for a one-way transition such as close or shutdown. At most one call of [trySet] returns `true` (exactly one,
 * unless [set] set the flag first), so work guarded by it runs once even when several threads race to do it:
 *
 * ```
 * override fun close() {
 *     if (!closed.trySet()) return
 *     releaseResources()
 * }
 * ```
 *
 * When no work depends on which call set the flag, mark the transition with [set] instead.
 */
class OnceFlag {
    private val flag = AtomicBoolean(false)

    /**
     * Whether the flag has been set.
     *
     * It turns `true` as soon as [trySet] wins, before the work that call guards runs, so it says the work was claimed,
     * not that it finished. For a value computed once, use `lazy`; to wait until work has finished, use a latch or a
     * `Deferred`.
     */
    val isSet: Boolean
        get() = flag.load()

    /** Sets the flag. Returns `true` only for the call that set it; every later call returns `false`. */
    fun trySet(): Boolean = flag.compareAndSet(expectedValue = false, newValue = true)

    /**
     * Sets the flag if it is not set yet; calling it again does nothing. A later [trySet] returns `false`. Use [trySet]
     * instead when the caller must know whether it was the one that set the flag.
     */
    fun set() {
        flag.store(true)
    }
}
