// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

// RED: a deliberately non-atomic version, so the contention tests can show they catch lost updates.
class SharedValue<T>(initial: T) {
    var value: T = initial
        private set

    fun set(newValue: T) {
        value = newValue
    }

    fun exchange(newValue: T): T {
        val previous = value
        value = newValue
        return previous
    }

    fun update(transform: (T) -> T): T {
        val next = transform(value)
        value = next
        return next
    }
}
