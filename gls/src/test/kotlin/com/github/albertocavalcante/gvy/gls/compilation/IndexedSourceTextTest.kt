// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndexedSourceTextTest {
    @Test
    fun `strict decoding preserves the original text over short reads`(@TempDir root: Path) {
        val content = "\uFEFF//\r\n" + "Xé測😀\r\n".repeat(2048)
        val path = Files.writeString(root.resolve("unicode"), content)
        val expected = IndexedSourceText(content)
        assertEquals(Files.size(path), expected.utf8Size)
        val channel = CountingChannel(Files.newByteChannel(path))
        assertSame(expected, expected.readDisk(path, allowReplacement = true, {}) { channel })
        assertEquals(expected.utf8Size, channel.bytes)
        assertTrue(channel.maxRequested in 1..DEFAULT_BUFFER_SIZE)
        assertEquals(1, channel.closes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "a", "original"])
    fun `growth after open consumes one sentinel`(content: String, @TempDir root: Path) {
        val path = Files.writeString(root.resolve("growing"), content)
        val expected = IndexedSourceText(content)
        lateinit var channel: CountingChannel
        val result = expected.readDisk(path, allowReplacement = true, {}) {
            channel = CountingChannel(Files.newByteChannel(path))
            if (content.isEmpty()) {
                Files.writeString(path, "new", StandardOpenOption.APPEND)
            } else {
                channel.afterFirstRead = { Files.writeString(path, "new", StandardOpenOption.APPEND) }
            }
            channel
        }
        assertSame(IndexedTextRead.Changed, result)
        assertEquals(expected.utf8Size + 1, channel.bytes)
        assertTrue(channel.maxRequested in 1..maxOf(1, content.length))
        assertEquals(1, channel.closes)
    }

    @Test
    fun `growth never splits a new character at the old decoder boundary`(@TempDir root: Path) {
        val path = Files.writeString(root.resolve("saved"), "x")
        val source = IndexedSourceText("x")
        val result = source.readDisk(path, true, {}) {
            val channel = Files.newByteChannel(path)
            Files.writeString(path, "é")
            channel
        }
        assertSame(IndexedTextRead.Changed, result)
    }

    @Test
    fun `size and presence checks can finish before opening a channel`(@TempDir root: Path) {
        val path = root.resolve("saved")
        val expected = IndexedSourceText("a")
        assertFalse(IndexedSourceText.isPresent(path.toUri(), null))
        Files.writeString(path, "// hello\n".repeat(2048))
        assertTrue(IndexedSourceText.isPresent(path.toUri(), null))
        for (allowReplacement in listOf(true, false)) {
            val result = expected.readDisk(path, allowReplacement, {}) { error("Larger file was opened") }
            assertSame(IndexedTextRead.Changed, result)
        }
        Files.writeString(path, "")
        assertSame(IndexedTextRead.Changed, expected.readDisk(path, false, {}) { error("Short file was opened") })
        assertFalse(IndexedSourceText.isPresent(root.toUri(), null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "A", "ab", "XY", "測"])
    fun `a rebuild can use changed text within the original byte budget`(replacement: String, @TempDir root: Path) {
        val path = Files.writeString(root.resolve("replacement"), replacement)
        val original = IndexedSourceText("abc")
        val channel = CountingChannel(Files.newByteChannel(path))
        val found = assertIs<IndexedSourceText>(original.readDisk(path, true, {}) { channel })
        assertEquals(replacement, found.content)
        assertEquals(Files.size(path), found.utf8Size)
        assertEquals(found.utf8Size, channel.bytes)
        assertEquals(1, channel.closes)
        assertFalse(original.matches(path.toUri(), null) {})
        assertTrue(found.matches(path.toUri(), null) {})
    }

    @Test
    fun `a short read followed by truncation returns only decoded text`(@TempDir root: Path) {
        val content = "x".repeat(8192)
        val path = Files.writeString(root.resolve("truncated"), content)
        val expected = IndexedSourceText(content)
        val channel = CountingChannel(Files.newByteChannel(path))
        channel.afterFirstRead = {
            Files.newByteChannel(path, StandardOpenOption.WRITE).use { it.truncate(channel.bytes) }
        }
        val found = assertIs<IndexedSourceText>(expected.readDisk(path, true, {}) { channel })
        assertTrue(channel.bytes in 1 until expected.utf8Size)
        assertEquals(content.take(channel.bytes.toInt()), found.content)
        assertEquals(1, channel.closes)
    }

    @Test
    fun `equal size invalid UTF8 fails decoding and closes the channel`(@TempDir root: Path) {
        val path = Files.write(root.resolve("invalid"), byteArrayOf(0xC3.toByte()))
        val channel = CountingChannel(Files.newByteChannel(path))
        assertFailsWith<MalformedInputException> {
            IndexedSourceText("x").readDisk(path, false, {}) { channel }
        }
        assertEquals(1L, channel.bytes)
        assertEquals(1, channel.closes)
    }

    @Test
    fun `cancellation after the first physical read stops decoding and closes the channel`(@TempDir root: Path) {
        val content = "stream".repeat(2048)
        val path = Files.writeString(root.resolve("cancel"), content)
        val channel = CountingChannel(Files.newByteChannel(path))
        val cancelled = CancellationException("request cancelled")
        val check = { if (channel.bytes > 0) throw cancelled }
        val error = assertFailsWith<CancellationException> {
            IndexedSourceText(content).readDisk(path, true, check) { channel }
        }
        assertSame(cancelled, error)
        assertEquals(1, channel.reads)
        assertEquals(1, channel.closes)
    }

    @Test
    fun `open text retains exact UTF16 identity even for isolated surrogates`(@TempDir root: Path) {
        val uri = root.resolve("open").toUri()
        val source = IndexedSourceText("\uD800")
        assertEquals(1L, source.utf8Size)
        assertTrue(source.matches(uri, "\uD800") {})
        assertFalse(source.matches(uri, "?") {})
    }

    private class CountingChannel(private val file: ReadableByteChannel) : ReadableByteChannel {
        var bytes = 0L
        var closes = 0
        var reads = 0
        var maxRequested = 0
        var afterFirstRead: () -> Unit = {}

        override fun read(dst: ByteBuffer): Int {
            maxRequested = maxOf(maxRequested, dst.remaining())
            val limit = dst.limit()
            dst.limit(dst.position() + minOf(3, dst.remaining()))
            val count = try {
                file.read(dst)
            } finally {
                dst.limit(limit)
            }
            if (count > 0) bytes += count
            reads++
            if (reads == 1) afterFirstRead()
            return count
        }

        override fun isOpen() = file.isOpen

        override fun close() {
            closes++
            file.close()
        }
    }
}
