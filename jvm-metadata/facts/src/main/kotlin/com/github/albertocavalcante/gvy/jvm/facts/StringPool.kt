// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.Arrays

/** Encodes [text] as UTF-8, refusing a string with an unpaired surrogate instead of replacing it. */
internal fun encodeUtf8Strict(text: String): ByteArray {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        val buffer = encoder.encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("A string of ${text.length} chars is not valid Unicode text", error)
    }
}

internal fun newStrictUtf8Decoder(): CharsetDecoder = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)

/** Decodes [length] bytes at [start]; malformed or overlong UTF-8 and encoded surrogates reject as corrupt. */
internal fun decodeUtf8Strict(decoder: CharsetDecoder, bytes: ByteArray, start: Int, length: Int): String = try {
    decoder.decode(ByteBuffer.wrap(bytes, start, length)).toString()
} catch (ignored: CharacterCodingException) {
    reject(RejectReason.CORRUPT)
}

/** The strings of a file, in the order of the file's pool. */
internal class StringPool(private val strings: Array<String>) {
    val size: Int get() = strings.size

    operator fun get(index: Int): String = strings[index]
}

/**
 * Builds the pool of a file: the unique strings, sorted by their UTF-8 bytes (unsigned, byte by byte), so the pool
 * and every table sorted by pool index are independent of the order the strings were added in.
 */
internal class StringPoolWriter private constructor(private val indices: Map<String, Int>, val raw: ByteArray) {
    fun indexOf(text: String): Int = checkNotNull(indices[text]) { "A string of ${text.length} chars is not pooled" }

    companion object {
        fun of(strings: Set<String>): StringPoolWriter {
            val encoded = strings.map { text -> text to encodeUtf8Strict(text) }
            for ((text, bytes) in encoded) {
                require(bytes.size <= FactsFormat.MAX_STRING_BYTES) {
                    "A string of ${text.length} chars needs ${bytes.size} bytes; the limit is " +
                        "${FactsFormat.MAX_STRING_BYTES}"
                }
            }
            val sorted = encoded.sortedWith { left, right -> Arrays.compareUnsigned(left.second, right.second) }
            val sink = ByteSink()
            val indices = HashMap<String, Int>(sorted.size * 2)
            sink.writeVarint(sorted.size)
            sorted.forEachIndexed { index, (text, bytes) ->
                indices[text] = index
                sink.writeVarint(bytes.size)
                sink.writeBytes(bytes)
            }
            return StringPoolWriter(indices, sink.toByteArray())
        }
    }
}

/**
 * Decodes the pool section. Each length is checked against the limit and the remaining bytes before its string is
 * read, and the strings must be strictly increasing by UTF-8 bytes, which also makes them unique.
 */
internal fun decodeStringPool(section: RawSection): StringPool {
    val cursor = section.cursor()
    val count = cursor.readVarint()
    cursor.requireCount(count, 1)
    val decoder = newStrictUtf8Decoder()
    val strings = ArrayList<String>(count)
    var previousStart = 0
    var previousLength = -1
    repeat(count) {
        val length = cursor.readVarint()
        requireThat(length <= FactsFormat.MAX_STRING_BYTES, RejectReason.OVERSIZED)
        val start = cursor.skip(length)
        if (previousLength >= 0) {
            val order = Arrays.compareUnsigned(
                section.bytes,
                previousStart,
                previousStart + previousLength,
                section.bytes,
                start,
                start + length,
            )
            requireThat(order < 0, RejectReason.CORRUPT)
        }
        previousStart = start
        previousLength = length
        strings += decodeUtf8Strict(decoder, section.bytes, start, length)
    }
    cursor.requireAtEnd()
    return StringPool(strings.toTypedArray())
}
