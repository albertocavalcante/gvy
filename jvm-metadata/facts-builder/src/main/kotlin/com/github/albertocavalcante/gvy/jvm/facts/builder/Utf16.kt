// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

/** Whether this string can be stored in a facts file; the stub accepts everything until the rule exists. */
internal fun String.isStorable(): Boolean = length >= 0

/** Whether this string can be written to a facts file, which accepts only well-formed UTF-16. */
internal fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            char.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}
