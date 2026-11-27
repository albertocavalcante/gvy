// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat

private const val ONE_BYTE_LIMIT = 0x80
private const val TWO_BYTE_LIMIT = 0x800
private const val THREE_BYTES = 3
private const val SURROGATE_PAIR_BYTES = 4

/**
 * Whether the facts format can store this string: well-formed UTF-16 (the format has no encoding for an unpaired
 * surrogate) whose UTF-8 form has at most [FactsFormat.MAX_STRING_BYTES] bytes. The length is counted without
 * encoding. A string read from a class file can break either rule: ASM decodes leniently, and when a constant ends in
 * the first byte of a multi-byte character it reads on into the bytes after the constant.
 */
internal fun String.isStorable(): Boolean {
    var bytes = 0
    var index = 0
    while (index < length && bytes <= FactsFormat.MAX_STRING_BYTES) {
        val char = this[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                bytes += SURROGATE_PAIR_BYTES
                index += 2
            }
            char.isLowSurrogate() -> return false
            else -> {
                bytes += utf8Width(char)
                index++
            }
        }
    }
    return bytes <= FactsFormat.MAX_STRING_BYTES
}

/** The UTF-8 width of a char that is not a surrogate; NUL is one byte, as the facts format writes it. */
private fun utf8Width(char: Char): Int = when {
    char.code < ONE_BYTE_LIMIT -> 1
    char.code < TWO_BYTE_LIMIT -> 2
    else -> THREE_BYTES
}
