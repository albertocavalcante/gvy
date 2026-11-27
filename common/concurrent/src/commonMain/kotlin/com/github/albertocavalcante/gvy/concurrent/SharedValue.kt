// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package com.github.albertocavalcante.gvy.concurrent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A value that threads replace as a whole, such as a configuration or an immutable snapshot.
 *
 * A reader sees either the old or the new value, never a mix. Keep fields that change together in one immutable value
 * rather than in several `SharedValue`s, which a reader could see at different moments.
 *
 * Derive the next value from the current one with [update]: reading [value] and then calling [set] can lose a change
 * made in between. There is deliberately no compare-and-set: it compares by reference, so it would fail silently for a
 * boxed number or an equal but distinct object.
 */
class SharedValue<T>(initial: T) {
    private val reference = AtomicReference(initial)

    /** The current value. */
    val value: T
        get() = reference.load()

    /** Replaces the value. */
    fun set(newValue: T) {
        reference.store(newValue)
    }

    /** Replaces the value and returns the one it replaced, for example to close it. */
    fun exchange(newValue: T): T {
        // PROBE: deliberately broken load then store.
        val previous = reference.load()
        reference.store(newValue)
        return previous
    }

    /**
     * Replaces the value with the result of [transform] applied to it, and returns the new value.
     *
     * When another thread changes the value first, [transform] runs again on the newer value, so it must not have side
     * effects.
     */
    fun update(transform: (T) -> T): T {
        // PROBE: deliberately broken; no compare-and-set.
        val next = transform(reference.load())
        reference.store(next)
        return next
    }
}
