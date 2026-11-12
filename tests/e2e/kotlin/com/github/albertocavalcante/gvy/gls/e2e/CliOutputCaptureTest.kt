// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CliOutputCaptureTest {
    @Test
    fun `complete UTF8 output at the exact byte boundary is preserved on both streams`() {
        val text = "é".repeat(LIMIT_BYTES / 2)
        val process = StreamProcess(ByteArrayInputStream(text.toByteArray()), ByteArrayInputStream(text.toByteArray()))

        CliOutputCapture(LIMIT_BYTES).use { capture ->
            assertThat(capture.await(process, WAIT_SECONDS)).isEqualTo(CliCommandOutput(0, text, text))
        }
    }

    @Test
    fun `ordinary large output within the default budget is complete on both streams`() {
        val text = "x".repeat(LEGITIMATE_OUTPUT_BYTES)
        val process = StreamProcess(ByteArrayInputStream(text.toByteArray()), ByteArrayInputStream(text.toByteArray()))

        CliOutputCapture(configuredCliOutputLimit(null)).use { capture ->
            assertThat(capture.await(process, WAIT_SECONDS)).isEqualTo(CliCommandOutput(0, text, text))
        }
    }

    @Test
    fun `a reader failure wakes a blocked process wait and releases its owned workers`() {
        val waiterEntered = CountDownLatch(1)
        val releaseWaiter = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val waiter = AtomicReference<Thread>()
        val failure = IOException("stdout reader failed")
        val stdout = object : InputStream() {
            override fun read(): Int {
                reader.set(Thread.currentThread())
                check(waiterEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Process waiter never entered" }
                throw failure
            }
        }
        val process = StreamProcess(stdout).apply {
            alive = true
            onWait = {
                waiter.compareAndSet(null, Thread.currentThread())
                waiterEntered.countDown()
                releaseWaiter.await()
                true
            }
            onDestroy = releaseWaiter::countDown
        }

        try {
            assertThatThrownBy { execute(process) }.isSameAs(failure)
            assertThat(process.alive).isFalse()
            assertThat(process.closedStreams).isEqualTo(STREAM_COUNT)
            assertThat(reader.get().isAlive).isFalse()
            assertThat(waiter.get().isAlive).isFalse()
        } finally {
            releaseWaiter.countDown()
            waiter.get()?.join(JOIN_TIMEOUT_MS)
        }
    }

    @Test
    fun `a cap failure retains bounded diagnostics and reports failed direct process cleanup`() {
        val cleanupFailure = IOException("stdin close failed")
        val process = StreamProcess(ByteArrayInputStream(ByteArray(OVER_LIMIT_BYTES * 2) { 'x'.code.toByte() })).apply {
            stdinCloseFailure = cleanupFailure
        }

        val observed = checkNotNull(catchThrowable { execute(process, OVER_LIMIT_BYTES) })

        assertThat(observed).isInstanceOf(CliOutputLimitExceeded::class.java)
            .hasMessageContaining("stdout")
            .hasMessageContaining("byte limit")
            .hasMessageContaining("observed at least ${OVER_LIMIT_BYTES + 1}")
            .hasMessageContaining("cleanup unconfirmed")
        assertThat(observed.message.orEmpty().length).isLessThan(MAX_DIAGNOSTIC_CHARACTERS)
        assertThat(observed.suppressed).containsExactly(cleanupFailure)
    }

    @Test
    fun `a reader that ignores cancellation cannot report confirmed cleanup`() {
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val stdout = object : InputStream() {
            override fun read(): Int {
                reader.set(Thread.currentThread())
                readerEntered.countDown()
                while (true) {
                    try {
                        releaseReader.await()
                        return -1
                    } catch (_: InterruptedException) {
                        // This control deliberately models a stream that does not unblock on interruption.
                    }
                }
            }
        }
        val stderr = object : ByteArrayInputStream(ByteArray(OVER_LIMIT_BYTES)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(readerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Blocking reader never entered" }
                return super.read(buffer, offset, length)
            }
        }
        val capture = CliOutputCapture(LIMIT_BYTES, Duration.ofMillis(SHORT_CLEANUP_MS))
        try {
            assertThatThrownBy { capture.await(StreamProcess(stdout, stderr), WAIT_SECONDS) }
                .isInstanceOf(CliOutputLimitExceeded::class.java)
            assertThatThrownBy { capture.close() }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("cleanup is not confirmed")
            assertThat(reader.get().isAlive).isTrue()
        } finally {
            releaseReader.countDown()
            reader.get()?.join(JOIN_TIMEOUT_MS)
            capture.close()
        }
        assertThat(reader.get().isAlive).isFalse()
    }

    @Test
    fun `configured output limits use a bounded positive decimal range`() {
        assertThat(configuredCliOutputLimit(null)).isEqualTo(DEFAULT_LIMIT_BYTES)
        assertThat(configuredCliOutputLimit("1")).isEqualTo(1)
        assertThat(configuredCliOutputLimit(MAX_CLI_OUTPUT_BYTES.toString())).isEqualTo(MAX_CLI_OUTPUT_BYTES)
        listOf("", " ", "0", "-1", "+1", "1.0", "١", "${MAX_CLI_OUTPUT_BYTES + 1}", "999999999999999999999").forEach {
            assertThatThrownBy { configuredCliOutputLimit(it) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("1 to $MAX_CLI_OUTPUT_BYTES")
        }
    }

    private fun execute(process: Process, limit: Int = LIMIT_BYTES) {
        val executor = CliCommandStepExecutor({ limit }) { process }
        val context = ScenarioContext(
            definition = ScenarioDefinition(Scenario("cli-output-control", steps = emptyList()), "scenario.yaml"),
            session = mockk(),
            workspace = ScenarioWorkspace(Path.of(".")),
            jsonPathConfig = Configuration.defaultConfiguration(),
        )
        executor.execute(ScenarioStep.CliCommand("tool", timeoutSeconds = WAIT_SECONDS), context)
    }

    private class StreamProcess(stdout: InputStream, stderr: InputStream = ByteArrayInputStream(byteArrayOf())) :
        Process() {
        var alive = false
        var closedStreams = 0
        var stdinCloseFailure: IOException? = null
        var onWait: () -> Boolean = { true }
        var onDestroy: () -> Unit = {}
        private val output = tracked(stdout)
        private val errors = tracked(stderr)
        private val input = object : ByteArrayOutputStream() {
            override fun close() {
                closedStreams++
                stdinCloseFailure?.let { throw it }
            }
        }

        private fun tracked(stream: InputStream): InputStream = object : java.io.FilterInputStream(stream) {
            override fun close() {
                closedStreams++
                super.close()
            }
        }

        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = errors
        override fun getOutputStream(): OutputStream = input
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = onWait()
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            alive = false
            onDestroy()
        }
        override fun destroyForcibly(): Process = apply { destroy() }
    }

    private companion object {
        const val LIMIT_BYTES = 16
        const val OVER_LIMIT_BYTES = 4096
        const val LEGITIMATE_OUTPUT_BYTES = 65_536
        const val DEFAULT_LIMIT_BYTES = 1024 * 1024
        const val MAX_DIAGNOSTIC_CHARACTERS = 1024
        const val STREAM_COUNT = 3
        const val WAIT_SECONDS = 5L
        const val JOIN_TIMEOUT_MS = 5000L
        const val SHORT_CLEANUP_MS = 50L
    }
}
