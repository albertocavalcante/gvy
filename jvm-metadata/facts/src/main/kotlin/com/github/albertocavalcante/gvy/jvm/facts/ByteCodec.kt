// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.io.ByteArrayOutputStream

private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUATION = 0x80
private const val VARINT_PAYLOAD_BITS = 7
private const val VARINT_MAX_BYTES = 5

/** The fifth byte of a varint carries bits 28 to 30, so a non-negative [Int] allows at most this value there. */
private const val VARINT_LAST_BYTE_MAX = 0x07
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8

/** Thrown inside the codec for malformed input; the public entry points turn it into a typed rejection. */
internal class Rejection(val reason: RejectReason) : Exception(reason.name, null, false, false)

internal fun reject(reason: RejectReason): Nothing = throw Rejection(reason)

internal fun requireThat(condition: Boolean, reason: RejectReason) {
    if (!condition) reject(reason)
}

/** Picks the enum constant whose wire code is [code], or rejects the input as corrupt. */
internal inline fun <E> List<E>.decodeEnum(code: Int, codeOf: (E) -> Int): E =
    firstOrNull { codeOf(it) == code } ?: reject(RejectReason.CORRUPT)

/** Appends little-endian integers, minimal unsigned LEB128 varints and raw bytes. */
internal class ByteSink {
    private val out = ByteArrayOutputStream()

    fun writeByte(value: Int) {
        out.write(value)
    }

    fun writeBytes(bytes: ByteArray) {
        out.write(bytes, 0, bytes.size)
    }

    /** Writes the low [byteCount] bytes of [value], least significant first. */
    fun writeLe(value: Long, byteCount: Int) {
        repeat(byteCount) { index ->
            out.write(((value ushr (index * BYTE_BITS)) and BYTE_MASK.toLong()).toInt())
        }
    }

    /** Writes a non-negative [value] in the shortest form of at most five bytes. */
    fun writeVarint(value: Int) {
        require(value >= 0) { "A varint must not be negative: $value" }
        var rest = value
        while (rest > VARINT_PAYLOAD_MASK) {
            out.write((rest and VARINT_PAYLOAD_MASK) or VARINT_CONTINUATION)
            rest = rest ushr VARINT_PAYLOAD_BITS
        }
        out.write(rest)
    }

    /** Writes 0 for null, otherwise the index plus one. */
    fun writeOptionalIndex(index: Int?) {
        writeVarint(if (index == null) 0 else index + 1)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Reads the bytes from `start` up to `end` of [bytes]. Every read that would pass `end` rejects with [overrun],
 * so a short buffer is a typed rejection and never an exception from the array.
 */
internal class ByteCursor(
    private val bytes: ByteArray,
    start: Int,
    private val end: Int,
    private val overrun: RejectReason,
) {
    var position: Int = start
        private set

    val remaining: Int get() = end - position

    fun readByte(): Int {
        val start = skip(1)
        return bytes[start].toInt() and BYTE_MASK
    }

    /** Reads [byteCount] bytes, least significant first, as a non-negative value of at most 8 bytes. */
    fun readLe(byteCount: Int): Long {
        val start = skip(byteCount)
        var value = 0L
        repeat(byteCount) { index ->
            value = value or ((bytes[start + index].toLong() and BYTE_MASK.toLong()) shl (index * BYTE_BITS))
        }
        return value
    }

    /** Reads a minimal varint of at most five bytes that fits a non-negative [Int]; anything else is corrupt. */
    fun readVarint(): Int {
        var value = 0
        for (index in 0 until VARINT_MAX_BYTES) {
            val byte = readByte()
            requireThat(index < VARINT_MAX_BYTES - 1 || byte <= VARINT_LAST_BYTE_MAX, RejectReason.CORRUPT)
            value = value or ((byte and VARINT_PAYLOAD_MASK) shl (index * VARINT_PAYLOAD_BITS))
            if ((byte and VARINT_CONTINUATION) == 0) {
                requireThat(index == 0 || byte != 0, RejectReason.CORRUPT)
                return value
            }
        }
        reject(RejectReason.CORRUPT)
    }

    /** Reads a varint that must be below [limit], for example an index into a table of [limit] entries. */
    fun readIndex(limit: Int): Int {
        val index = readVarint()
        requireThat(index < limit, RejectReason.INDEX_OUT_OF_RANGE)
        return index
    }

    /** Reads a varint that must be at most [max]. */
    fun readAtMost(max: Int): Int {
        val value = readVarint()
        requireThat(value <= max, RejectReason.CORRUPT)
        return value
    }

    /** Advances over [length] bytes and returns the position they start at. */
    fun skip(length: Int): Int {
        requireThat(length in 0..remaining, overrun)
        val start = position
        position += length
        return start
    }

    fun readBytes(length: Int): ByteArray {
        val start = skip(length)
        return bytes.copyOfRange(start, start + length)
    }

    /**
     * Checks that [count] items of at least [minBytes] bytes each can still fit in what is left. Call it before
     * allocating anything sized by [count]: a count taken from the input then never exceeds the input's size.
     */
    fun requireCount(count: Int, minBytes: Int) {
        requireThat(count <= remaining / minBytes, overrun)
    }

    /** Rejects trailing bytes: a section holds exactly its declared content. */
    fun requireAtEnd() {
        requireThat(position == end, RejectReason.CORRUPT)
    }
}

/** A decompressed section: [length] bytes of [bytes] from [offset]. */
internal class RawSection(val bytes: ByteArray, val offset: Int, val length: Int) {
    fun cursor(): ByteCursor = ByteCursor(bytes, offset, offset + length, RejectReason.CORRUPT)
}
