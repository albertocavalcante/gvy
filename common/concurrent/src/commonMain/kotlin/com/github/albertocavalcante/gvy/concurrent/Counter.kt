// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

// RED: a deliberately non-atomic version, so the contention tests can show they catch lost updates.
class Counter(initial: Long = 0) {
    var value: Long = initial
        private set

    fun increment(): Long {
        value += 1
        return value
    }
}
