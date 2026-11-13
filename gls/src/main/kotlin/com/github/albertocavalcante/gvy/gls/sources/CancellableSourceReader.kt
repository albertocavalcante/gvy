// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.io.FilterReader
import java.io.Reader

/** Parser cancellation stays with its producer; closing a reader always releases the underlying input. */
internal class CancellableSourceReader(reader: Reader, private val checkCancelled: () -> Unit) : FilterReader(reader) {
    override fun read(): Int {
        checkCancelled()
        return super.read()
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        checkCancelled()
        return super.read(buffer, offset, length)
    }

    override fun skip(count: Long): Long {
        checkCancelled()
        return super.skip(count)
    }
}
