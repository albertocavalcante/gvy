// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CancellationException
import java.io.FilterReader
import java.io.Reader

/** Parser cancellation stays with its producer; closing a reader always releases the underlying input. */
internal class CancellableSourceReader(reader: Reader, private val checkCancelled: () -> Unit) : FilterReader(reader) {
    var cancellation: CancellationException? = null
        private set

    override fun read(): Int {
        verifyCancellation()
        return super.read()
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        verifyCancellation()
        return super.read(buffer, offset, length)
    }

    override fun skip(count: Long): Long {
        verifyCancellation()
        return super.skip(count)
    }

    private fun verifyCancellation() {
        try {
            checkCancelled()
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            throw cancelled
        }
    }
}
