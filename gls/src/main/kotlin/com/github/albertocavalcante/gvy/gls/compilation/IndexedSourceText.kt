// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import java.io.IOException
import java.io.Reader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal sealed interface IndexedTextRead {
    data object Missing : IndexedTextRead
    data object Changed : IndexedTextRead
}

/** An index producer's exact text and its byte budget for subsequent reads from disk. */
internal class IndexedSourceText private constructor(val content: String, val utf8Size: Long) : IndexedTextRead {
    constructor(content: String) : this(content, utf8ByteLength(content))

    fun matches(uri: URI, openText: String?, checkCancelled: () -> Unit): Boolean =
        read(uri, openText, allowReplacement = false, checkCancelled) === this

    fun read(uri: URI, openText: String?, allowReplacement: Boolean, checkCancelled: () -> Unit): IndexedTextRead {
        checkCancelled()
        if (openText != null) {
            return when {
                openText == content -> this
                allowReplacement -> IndexedSourceText(openText)
                else -> IndexedTextRead.Changed
            }
        }
        val path = UriPathConverter.toPath(uri) ?: return IndexedTextRead.Missing
        return readDisk(path, allowReplacement, checkCancelled)
    }

    internal fun readDisk(
        path: Path,
        allowReplacement: Boolean,
        checkCancelled: () -> Unit,
        open: (Path) -> ReadableByteChannel = { Files.newByteChannel(it) },
    ): IndexedTextRead {
        checkCancelled()
        val size = regularFileSize(path) ?: return IndexedTextRead.Missing
        if (size > utf8Size || (!allowReplacement && size != utf8Size)) return IndexedTextRead.Changed
        checkCancelled()
        val channel = try {
            open(path)
        } catch (_: NoSuchFileException) {
            return IndexedTextRead.Missing
        }
        val bounded = IndexedTextChannel(channel, utf8Size, checkCancelled)
        return Channels.newReader(bounded, Charsets.UTF_8.newDecoder(), DEFAULT_BUFFER_SIZE).use { reader ->
            try {
                decode(reader, bounded, allowReplacement)
            } catch (_: SourceGrew) {
                IndexedTextRead.Changed
            }
        }
    }

    /** Keep the producer String when equal. Only a rebuild of a different source needs a new String. */
    private fun decode(reader: Reader, channel: IndexedTextChannel, allowReplacement: Boolean): IndexedTextRead {
        val chars = CharArray(DEFAULT_BUFFER_SIZE)
        var prefix = 0
        var equal = true
        var replacement: StringBuilder? = null
        while (true) {
            val count = reader.read(chars)
            if (count < 0) break
            if (equal) {
                equal = count <= content.length - prefix && (0 until count).all { chars[it] == content[prefix + it] }
                if (equal) {
                    prefix += count
                } else if (allowReplacement) {
                    replacement = StringBuilder().append(content, 0, prefix)
                }
            }
            replacement?.append(chars, 0, count)
        }
        return when {
            equal && prefix == content.length -> this
            allowReplacement -> {
                val text = replacement?.toString() ?: content.substring(0, prefix)
                IndexedSourceText(text, channel.bytes)
            }
            else -> IndexedTextRead.Changed
        }
    }

    companion object {
        fun isPresent(uri: URI, openText: String?): Boolean {
            if (openText != null) return true
            val path = UriPathConverter.toPath(uri) ?: return false
            return regularFileSize(path) != null
        }

        private fun regularFileSize(path: Path): Long? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java).takeIf { it.isRegularFile }?.size()
        } catch (_: NoSuchFileException) {
            null
        }
    }
}

/** Signal growth before the decoder can interpret a partial character at the byte boundary as EOF. */
private class SourceGrew : IOException()

/** Limit each channel request below the decoder. At the budget boundary read one byte to distinguish EOF. */
private class IndexedTextChannel(
    private val channel: ReadableByteChannel,
    private val budget: Long,
    private val checkCancelled: () -> Unit,
) : ReadableByteChannel by channel {
    var bytes = 0L
        private set

    override fun read(destination: ByteBuffer): Int {
        checkCancelled()
        if (!destination.hasRemaining()) return 0
        val remaining = budget - bytes
        val available = if (remaining == 0L) 1 else minOf(remaining, DEFAULT_BUFFER_SIZE.toLong()).toInt()
        val limit = destination.limit()
        val position = destination.position()
        destination.limit(position + minOf(available, destination.remaining()))
        val count = try {
            channel.read(destination)
        } finally {
            destination.limit(limit)
        }
        checkCancelled()
        if (count > 0 && remaining == 0L) {
            throw SourceGrew()
        }
        if (count > 0) bytes += count
        return count
    }
}

private const val ASCII_MAX = 0x7f
private const val UTF8_TWO_BYTE_MAX = 0x7ff
private const val UTF8_THREE_BYTES = 3L
private const val UTF8_FOUR_BYTES = 4L

private fun utf8ByteLength(text: String): Long = text.codePoints().mapToLong { point ->
    when {
        point <= ASCII_MAX -> 1L
        point <= UTF8_TWO_BYTE_MAX -> 2L
        point in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code -> 1L // JVM UTF-8 encoder replacement
        point <= Char.MAX_VALUE.code -> UTF8_THREE_BYTES
        else -> UTF8_FOUR_BYTES
    }
}.sum()
