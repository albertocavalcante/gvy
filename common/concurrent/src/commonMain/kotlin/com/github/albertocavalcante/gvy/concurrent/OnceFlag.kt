// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

// RED: a deliberately non-atomic version, so the contention tests can show they catch lost updates.
class OnceFlag {
    var isSet: Boolean = false
        private set

    fun trySet(): Boolean {
        if (isSet) return false
        isSet = true
        return true
    }
}
