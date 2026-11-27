// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val DEFLATE_BUFFER_BYTES = 8192

internal fun deflate(raw: ByteArray, level: Int): ByteArray {
    val deflater = Deflater(level)
    return try {
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFLATE_BUFFER_BYTES)
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            out.write(buffer, 0, written)
        }
        out.toByteArray()
    } finally {
        deflater.end()
    }
}

/**
 * Inflates one zlib stream from [length] bytes of [stored] at [offset] into exactly [rawLength] bytes. The output
 * array is sized from the declared length, which the caller has already capped, and never grows. A stream that
 * produces more or fewer bytes than declared, ends early, or leaves unread input behind is corrupt.
 */
internal fun inflateExactly(stored: ByteArray, offset: Int, length: Int, rawLength: Int): ByteArray {
    val inflater = Inflater()
    return try {
        inflater.setInput(stored, offset, length)
        val out = ByteArray(rawLength)
        var filled = 0
        while (filled < rawLength) {
            val produced = inflater.inflate(out, filled, rawLength - filled)
            if (produced == 0) break
            filled += produced
        }
        requireThat(filled == rawLength, RejectReason.CORRUPT)
        // The declared length is full: the stream must end here, with no further output and no trailing input.
        requireThat(inflater.inflate(ByteArray(1)) == 0, RejectReason.CORRUPT)
        requireThat(inflater.finished() && inflater.remaining == 0, RejectReason.CORRUPT)
        out
    } catch (ignored: DataFormatException) {
        reject(RejectReason.CORRUPT)
    } finally {
        inflater.end()
    }
}
