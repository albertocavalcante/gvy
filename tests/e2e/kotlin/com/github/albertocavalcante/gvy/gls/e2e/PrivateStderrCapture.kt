// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Collects bytes supplied by the existing stream owner; this class never reads a stream itself.
 * The cap applies before decoding, and callers must continue draining after the cap is reached.
 */
internal class PrivateStderrCapture(private val limit: Int) {
    private companion object {
        const val MAX_CAPTURE_BYTES = 256 * 1024
    }

    init {
        require(limit in 1..MAX_CAPTURE_BYTES)
    }

    private val bytes = ByteArray(limit)
    private var size = 0
    private var lost = false
    private var eof = false
    private var failed = false

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        check(!eof && !failed)
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset)
        val retained = minOf(length, limit - size)
        bytes.copyInto(this.bytes, size, offset, offset + retained)
        size += retained
        if (retained != length) lost = true
    }

    @Synchronized
    fun endOfInput() {
        check(!eof && !failed)
        eof = true
    }

    @Synchronized
    fun readFailure() {
        check(!eof && !failed)
        failed = true
    }

    @Synchronized
    fun snapshot(): PrivateStderrEvidence = PrivateStderrEvidence(bytes.copyOf(size), eof, failed, lost)
}

/** An immutable, private observation; raw bytes and decoded content never appear in summaries. */
internal class PrivateStderrEvidence internal constructor(
    bytes: ByteArray,
    val eof: Boolean,
    val readFailed: Boolean,
    val loss: Boolean,
) {
    val settled: Boolean = eof || readFailed
    val retainedBytes: Int = bytes.size
    val unterminatedTail: Boolean = bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()
    private val content: String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    val invalidUtf8: Boolean = content == null

    fun hasCompleteLine(line: String): Boolean {
        if (!settled || !eof || readFailed || loss || invalidUtf8 || unterminatedTail) return false
        if (line.isEmpty() || '\n' in line || '\r' in line) return false
        val decoded = content ?: return false
        return decoded.lineSequence().any { it == line }
    }
}
