// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.Base64
import java.util.Collections

/** Reasons the retained prefix cannot establish all bytes observed at the stream boundary. */
enum class WireEvidenceGap {
    BYTE_LIMIT,
    DELEGATE_FAILURE,
    SKIPPED_BYTES,
    STREAM_RESET,
    CONCURRENT_ACCESS,
    BYTE_COUNT_OVERFLOW,
    MUTATED_WRITE_BUFFER,
    DELEGATE_CONTRACT,
}

/** Original bytes, before any JSON decoder or SDK projection. No payload is logged automatically. */
class WireStreamEvidence internal constructor(
    val prefixBase64: String,
    val retainedBytes: Int,
    val transferredBytes: Long,
    val endOfInputObserved: Boolean,
    val inFlightOperations: Int,
    val gaps: Set<WireEvidenceGap>,
) {
    /** Applies only to completed stream operations so far, not peer delivery or scenario completion. */
    val losslessSoFar: Boolean get() = gaps.isEmpty() && transferredBytes == retainedBytes.toLong()
}

class WireEvidenceSnapshot internal constructor(val received: WireStreamEvidence, val transmitted: WireStreamEvidence)

/**
 * Passive client-boundary capture. The SDK retains sole responsibility for framing and decoding.
 *
 * Each direction retains a fixed-size prefix. After a gap it stops appending, so later bytes can
 * never be presented as a contiguous original prefix. Output records only writes which returned;
 * an exceptional write may have partially reached its delegate and is explicitly indeterminate.
 * Locks protect bookkeeping only, never delegate I/O or user callbacks.
 * As with the stream contracts, caller buffers must not be mutated concurrently and delegates
 * must synchronously transfer their supplied bytes. This observes that boundary, not a lower
 * physical transport. Detectable buffer mutation is reported conservatively as a gap.
 */
internal class BoundedWireCapture(maxBytesPerDirection: Int = DEFAULT_BYTES_PER_DIRECTION) {
    init {
        require(maxBytesPerDirection in 1..MAX_BYTES_PER_DIRECTION) { "Invalid wire evidence byte limit" }
    }

    private val received = StreamCapture(maxBytesPerDirection)
    private val transmitted = StreamCapture(maxBytesPerDirection)

    fun snapshot(): WireEvidenceSnapshot = WireEvidenceSnapshot(received.snapshot(), transmitted.snapshot())

    fun input(delegate: InputStream): InputStream = object : InputStream() {
        override fun read(): Int = received.operation {
            delegate.read().also { value ->
                when (value) {
                    -1 -> received.eof()
                    in 0..255 -> received.byte(value)
                    else -> received.gap(WireEvidenceGap.DELEGATE_CONTRACT)
                }
            }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = received.operation {
            delegate.read(bytes, offset, length).also { count ->
                received.readResult(bytes, offset, length, count)
            }
        }

        override fun skip(count: Long): Long = received.operation {
            delegate.skip(count).also { skipped ->
                when {
                    skipped < 0 || (skipped > 0 && skipped > count) ->
                        received.gap(WireEvidenceGap.DELEGATE_CONTRACT)
                    skipped > 0 -> received.skip(skipped)
                }
            }
        }

        override fun available(): Int = delegate.available()
        override fun markSupported(): Boolean = delegate.markSupported()
        override fun mark(readlimit: Int) = delegate.mark(readlimit)
        override fun reset() = received.operation {
            delegate.reset()
            received.gap(WireEvidenceGap.STREAM_RESET)
        }

        override fun close() = received.operation { delegate.close() }
    }

    fun output(delegate: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) = transmitted.operation {
            delegate.write(value)
            transmitted.byte(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) = transmitted.operation {
            val prepared = transmitted.prepareWrite(bytes, offset, length)
            delegate.write(bytes, offset, length)
            transmitted.completeWrite(bytes, offset, length, prepared)
        }

        override fun flush() = transmitted.operation { delegate.flush() }
        override fun close() = transmitted.operation { delegate.close() }
    }

    private companion object {
        const val DEFAULT_BYTES_PER_DIRECTION = 256 * 1024
        const val MAX_BYTES_PER_DIRECTION = 1024 * 1024
    }
}

private class StreamCapture(limit: Int) {
    private val prefix = ByteArray(limit)
    private var size = 0
    private var transferred = 0L
    private var gapBits = 0
    private var activeOperations = 0
    private var endOfInput = false

    fun <T> operation(action: () -> T): T {
        synchronized(this) {
            if (activeOperations++ > 0) gap(WireEvidenceGap.CONCURRENT_ACCESS)
        }
        try {
            return action()
        } catch (failure: Throwable) {
            // No exception conversion, formatting or retained Throwable graph, including fatal failures.
            gap(WireEvidenceGap.DELEGATE_FAILURE)
            throw failure
        } finally {
            synchronized(this) { activeOperations-- }
        }
    }

    @Synchronized
    fun byte(value: Int) {
        count(1)
        if (gapBits != 0) return
        if (size == prefix.size) gap(WireEvidenceGap.BYTE_LIMIT) else prefix[size++] = value.toByte()
    }

    @Synchronized
    fun readResult(bytes: ByteArray, offset: Int, length: Int, result: Int) {
        when {
            !validSlice(bytes, offset, length) || (length == 0 && result != 0) || result < -1 || result > length ->
                gap(WireEvidenceGap.DELEGATE_CONTRACT)
            result == -1 -> eof()
            else -> bytes(bytes, offset, result)
        }
    }

    @Synchronized
    fun bytes(bytes: ByteArray, offset: Int, length: Int) {
        if (!validSlice(bytes, offset, length)) {
            gap(WireEvidenceGap.DELEGATE_CONTRACT)
            return
        }
        count(length.toLong())
        if (gapBits != 0) return
        val retained = minOf(length, prefix.size - size)
        bytes.copyInto(prefix, size, offset, offset + retained)
        size += retained
        if (retained != length) gap(WireEvidenceGap.BYTE_LIMIT)
    }

    @Synchronized
    fun skip(count: Long) {
        count(count)
        gap(WireEvidenceGap.SKIPPED_BYTES)
    }

    @Synchronized
    fun prepareWrite(bytes: ByteArray, offset: Int, length: Int): Int {
        // Invalid arguments are still validated by the delegate, preserving its original failure.
        if (gapBits != 0 || !validSlice(bytes, offset, length)) return 0
        val retained = minOf(length, prefix.size - size)
        bytes.copyInto(prefix, size, offset, offset + retained)
        return retained
    }

    @Synchronized
    fun completeWrite(bytes: ByteArray, offset: Int, length: Int, prepared: Int) {
        if (!validSlice(bytes, offset, length)) {
            gap(WireEvidenceGap.DELEGATE_CONTRACT)
            return
        }
        count(length.toLong())
        if (gapBits != 0) return
        if (Arrays.mismatch(prefix, size, size + prepared, bytes, offset, offset + prepared) >= 0) {
            gap(WireEvidenceGap.MUTATED_WRITE_BUFFER)
            return
        }
        size += prepared
        if (prepared != length) gap(WireEvidenceGap.BYTE_LIMIT)
    }

    @Synchronized
    fun eof() {
        endOfInput = true
    }

    @Synchronized
    fun gap(reason: WireEvidenceGap) {
        gapBits = gapBits or (1 shl reason.ordinal)
    }

    @Synchronized
    fun snapshot(): WireStreamEvidence = WireStreamEvidence(
        Base64.getEncoder().encodeToString(prefix.copyOf(size)),
        size,
        transferred,
        endOfInput,
        activeOperations,
        Collections.unmodifiableSet(
            WireEvidenceGap.entries.filterTo(linkedSetOf()) { reason ->
                gapBits and (1 shl reason.ordinal) != 0
            },
        ),
    )

    private fun count(amount: Long) {
        if (Long.MAX_VALUE - transferred < amount) {
            transferred = Long.MAX_VALUE
            gap(WireEvidenceGap.BYTE_COUNT_OVERFLOW)
        } else {
            transferred += amount
        }
    }

    private fun validSlice(bytes: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= bytes.size - length
}
