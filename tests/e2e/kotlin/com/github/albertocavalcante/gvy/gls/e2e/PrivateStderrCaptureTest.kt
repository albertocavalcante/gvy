// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateStderrCaptureTest {
    @Test
    fun `complete split UTF-8 record is retained privately and snapshot is immutable`() {
        for (line in listOf("WARN café", "WARN 😀")) {
            val bytes = "$line\n".toByteArray(Charsets.UTF_8)
            for (split in 0..bytes.size) {
                val capture = PrivateStderrCapture(64)
                val input = bytes.copyOf()
                capture.append(input, 0, split)
                capture.append(input, split, input.size - split)
                val beforeEof = capture.snapshot()
                capture.endOfInput()
                val snapshot = capture.snapshot()
                assertTrue(snapshot.settled)
                assertTrue(snapshot.eof)
                assertFalse(snapshot.readFailed)
                assertFalse(snapshot.loss)
                assertFalse(snapshot.invalidUtf8)
                assertFalse(snapshot.unterminatedTail)
                assertTrue(snapshot.hasCompleteLine(line))
                input.fill(0)
                assertFalse(beforeEof.settled)
                assertTrue(snapshot.hasCompleteLine(line))
                assertFalse(snapshot.toString().contains(line))
            }
        }
    }

    @Test
    fun `byte overflow refuses evidence even when a complete line was retained`() {
        val capture = PrivateStderrCapture(5)
        val bytes = "WARN\nextra".toByteArray()
        capture.append(bytes, 0, bytes.size)
        capture.endOfInput()
        val snapshot = capture.snapshot()
        assertEquals(5, snapshot.retainedBytes)
        assertTrue(snapshot.loss)
        assertFalse(snapshot.hasCompleteLine("WARN"))
    }

    @Test
    fun `open stream read failure invalid UTF-8 and tail each refuse a match`() {
        val open = PrivateStderrCapture(64)
        val line = "WARN\n".toByteArray()
        open.append(line, 0, line.size)
        assertFalse(open.snapshot().settled)
        assertFalse(open.snapshot().hasCompleteLine("WARN"))

        val failed = PrivateStderrCapture(64)
        failed.append(line, 0, line.size)
        failed.readFailure()
        assertTrue(failed.snapshot().readFailed)
        assertFalse(failed.snapshot().hasCompleteLine("WARN"))

        val invalid = PrivateStderrCapture(64)
        invalid.append(byteArrayOf(0xc3.toByte(), 0x28, 0x0a), 0, 3)
        invalid.endOfInput()
        assertTrue(invalid.snapshot().invalidUtf8)
        assertFalse(invalid.snapshot().hasCompleteLine("("))

        val tail = PrivateStderrCapture(64)
        val tailBytes = "WARN\nunfinished".toByteArray()
        tail.append(tailBytes, 0, tailBytes.size)
        tail.endOfInput()
        assertTrue(tail.snapshot().unterminatedTail)
        assertFalse(tail.snapshot().hasCompleteLine("WARN"))
    }

    @Test
    fun `exact record matching rejects prefixes and empty evidence`() {
        val capture = PrivateStderrCapture(64)
        val bytes = "WARN one\nWARN two\n".toByteArray()
        capture.append(bytes, 0, bytes.size)
        capture.endOfInput()
        val snapshot = capture.snapshot()
        assertTrue(snapshot.hasCompleteLine("WARN one"))
        assertFalse(snapshot.hasCompleteLine("WARN"))
        assertFalse(snapshot.hasCompleteLine(""))
        assertFalse(snapshot.hasCompleteLine("WARN one\nWARN two"))
    }

    @Test
    fun `invalid budgets and invalid ranges fail before copying`() {
        assertFailsWith<IllegalArgumentException> { PrivateStderrCapture(0) }
        assertFailsWith<IllegalArgumentException> { PrivateStderrCapture(-1) }
        assertFailsWith<IllegalArgumentException> { PrivateStderrCapture(256 * 1024 + 1) }
        assertFailsWith<IllegalArgumentException> { PrivateStderrCapture(Int.MAX_VALUE) }
        val capture = PrivateStderrCapture(4)
        val input = "abc".toByteArray()
        assertFailsWith<IllegalArgumentException> { capture.append(input, -1, 1) }
        assertFailsWith<IllegalArgumentException> { capture.append(input, 0, Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { capture.append(input, Int.MAX_VALUE, 1) }
        capture.append(input, 1, 2)
        capture.append(byteArrayOf(10), 0, 1)
        capture.endOfInput()
        assertTrue(capture.snapshot().hasCompleteLine("bc"))
    }

    @Test
    fun `terminal states reject later mutation and snapshots do not expose bytes`() {
        val capture = PrivateStderrCapture(16)
        val input = "WARN\n".toByteArray()
        capture.append(input, 0, input.size)
        input.fill(0)
        capture.endOfInput()
        val evidence = capture.snapshot()
        assertTrue(evidence.hasCompleteLine("WARN"))
        assertFalse(evidence.toString().contains("WARN"))
        assertFailsWith<IllegalStateException> { capture.append(input, 0, input.size) }
        assertFailsWith<IllegalStateException> { capture.endOfInput() }
        assertFailsWith<IllegalStateException> { capture.readFailure() }

        val failed = PrivateStderrCapture(16)
        failed.readFailure()
        assertTrue(failed.snapshot().settled)
        assertFalse(failed.snapshot().eof)
        assertFailsWith<IllegalStateException> { failed.endOfInput() }
        assertFailsWith<IllegalStateException> { failed.append(input, 0, input.size) }
    }

    @Test
    fun `reader observes a coherent snapshot after writer settles`() {
        val capture = PrivateStderrCapture(16)
        val writer = Thread {
            val input = "WARN\n".toByteArray()
            capture.append(input, 0, input.size)
            capture.endOfInput()
        }
        writer.start()
        writer.join()
        val evidence = capture.snapshot()
        assertTrue(evidence.settled)
        assertTrue(evidence.eof)
        assertEquals(5, evidence.retainedBytes)
        assertTrue(evidence.hasCompleteLine("WARN"))
    }
}
