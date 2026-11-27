// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package com.github.albertocavalcante.gvy.concurrent

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A flag that is set at most once and never cleared, safe to share between threads.
 *
 * Use it for a one-way transition such as close or shutdown. If [set] has not set the flag, the first call of [trySet]
 * returns `true`; every other call returns `false`. So work guarded by [trySet] runs once even when several threads
 * race to do it:
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
     * Whether the flag has been set, by [trySet] or [set].
     *
     * It only says the flag was set. When [trySet] guards work, the flag is set before that work runs, so `isSet` means
     * the work was claimed, not finished. For a value computed once, use `lazy`; to wait until work has finished, use a
     * latch or a `Deferred`.
     */
    val isSet: Boolean
        get() = flag.load()

    /**
     * Sets the flag if it is not set yet. Returns `true` only for the call that set it, and `false` for every other
     * call.
     */
    fun trySet(): Boolean {
        // PROBE: deliberately broken check-then-set.
        if (flag.load()) return false
        flag.store(true)
        return true
    }

    /**
     * Sets the flag if it is not set yet; calling it again does nothing. A later [trySet] returns `false`. Use [trySet]
     * instead when the caller must know whether it was the one that set the flag.
     */
    fun set() {
        flag.store(true)
    }
}
