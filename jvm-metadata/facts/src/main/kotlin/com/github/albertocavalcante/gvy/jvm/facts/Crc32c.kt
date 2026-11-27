// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.util.zip.CRC32C

/** The CRC-32C (Castagnoli) of [length] bytes of [bytes] from [offset], as the 32 bits of an [Int]. */
internal fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int {
    val crc = CRC32C()
    crc.update(bytes, offset, length)
    return crc.value.toInt()
}
