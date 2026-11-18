// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BoundedProcessRunnerTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun requirePosixShell() {
        assumeFalse(BuildExecutableResolver.isWindows)
    }

    @Test
    fun `captures merged output and exit code`() {
        val result = BoundedProcessRunner.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"), tempDir, 10.seconds)

        assertFalse(result.timedOut)
        assertEquals(3, result.exitCode)
        assertTrue(result.output.contains("out") && result.output.contains("err"))
    }

    @Test
    fun `stdin is closed so a reader sees end of input`() {
        val result = BoundedProcessRunner.run(listOf("sh", "-c", "cat; echo done"), tempDir, 10.seconds)

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `timeout kills the process and its descendants`() {
        val started = System.nanoTime()
        val command = listOf("sh", "-c", "sleep 60 & echo \$!; wait")
        val result = BoundedProcessRunner.run(command, tempDir, 2.seconds)
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000

        assertTrue(result.timedOut)
        assertTrue(elapsedSeconds < 30, "run must return shortly after the timeout, took ${elapsedSeconds}s")
        val childPid = result.output.trim().lines().first().toLong()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false), "descendant must be killed")
    }

    @Test
    fun `output beyond the cap is truncated keeping head and tail`() {
        val script = "i=0; while [ \$i -lt 20000 ]; do echo line-\$i-xxxxxxxxxxxx; i=\$((i+1)); done"
        val result = BoundedProcessRunner.run(listOf("sh", "-c", script), tempDir, 30.seconds)

        assertEquals(0, result.exitCode)
        val limit = BoundedProcessRunner.HEAD_CHARS + BoundedProcessRunner.TAIL_CHARS
        assertTrue(result.output.length < limit + 200, "captured ${result.output.length} chars")
        assertTrue(result.output.startsWith("line-0-"))
        assertTrue(result.output.contains("characters of output truncated"))
        assertTrue(result.output.trimEnd().endsWith("line-19999-xxxxxxxxxxxx"))
    }

    @Test
    fun `interrupt kills the process and leaves no reader thread`() {
        val before = readerThreads()
        val pidFile = tempDir.resolve("pid")
        val outcome = interruptRun("echo \$\$ > '$pidFile'; sleep 60", pidFile)

        assertTrue(outcome.failure is InterruptedException)
        val pid = pidFile.readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the process must be killed")
        // Only readers started by this run count: other tests in the JVM may have their own.
        val leaked = readerThreads() - before
        assertEquals(emptySet<Thread>(), leaked.filter { it.isAlive }.toSet(), "reader must be joined")
    }

    @Test
    fun `interrupt closes the stream and interrupts a reader that stays blocked`() {
        // A process whose pipe never reaches EOF, as when an escaped descendant holds it open. The fake makes
        // the scenario identical on every OS: real pipes wake a blocked reader on some platforms but not others.
        val process = StuckProcess()
        val failure = AtomicReference<Throwable>()
        val runner = Thread {
            try {
                BoundedProcessRunner.run(listOf("stuck"), tempDir, 120.seconds, 200.milliseconds) { _, _ -> process }
            } catch (e: InterruptedException) {
                failure.set(e)
            }
        }
        runner.start()
        assertTrue(process.readerBlocked.await(20, TimeUnit.SECONDS), "the reader never started reading")
        runner.interrupt()
        runner.join(30_000)

        assertFalse(runner.isAlive, "run() must return after an interrupt")
        assertTrue(failure.get() is InterruptedException)
        assertTrue(process.destroyed, "the process must be killed")
        assertTrue(process.streamClosed, "a reader that does not drain must have its stream closed")
        assertTrue(process.readerInterrupted.await(10, TimeUnit.SECONDS), "a blocked reader must be interrupted")
    }

    /** A process that never exits and whose output stream blocks until it is interrupted. */
    private class StuckProcess : Process() {
        val readerBlocked = CountDownLatch(1)
        val readerInterrupted = CountDownLatch(1)

        @Volatile var streamClosed = false

        @Volatile var destroyed = false

        private val input = object : InputStream() {
            override fun read(): Int = block()

            override fun read(b: ByteArray, off: Int, len: Int): Int = block()

            override fun close() {
                streamClosed = true
            }

            private fun block(): Int {
                readerBlocked.countDown()
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (e: InterruptedException) {
                    readerInterrupted.countDown()
                    throw InterruptedIOException(e.message)
                }
                return -1
            }
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = throw UnsupportedOperationException()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            Thread.sleep(unit.toMillis(timeout))
            return false
        }

        override fun exitValue(): Int = throw IllegalThreadStateException()

        override fun destroy() {
            destroyed = true
        }

        override fun descendants(): Stream<ProcessHandle> = Stream.empty()
    }

    private class InterruptOutcome(val failure: Throwable?, val elapsedMillis: Long)

    /** Runs [script], interrupts the runner once every file in [ready] exists, and reports how run() ended. */
    private fun interruptRun(script: String, vararg ready: Path): InterruptOutcome {
        val failure = AtomicReference<Throwable>()
        val interruptedAt = AtomicLong()
        val finishedAt = AtomicLong()
        val runner = Thread {
            try {
                BoundedProcessRunner.run(listOf("sh", "-c", script), tempDir, 120.seconds)
            } catch (e: InterruptedException) {
                failure.set(e)
            } finally {
                finishedAt.set(System.nanoTime())
            }
        }
        runner.start()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (ready.any { !it.exists() || it.readText().isBlank() } && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(ready.all { it.exists() && it.readText().isNotBlank() }, "the script did not start in time")
        interruptedAt.set(System.nanoTime())
        runner.interrupt()
        runner.join(60_000)

        assertFalse(runner.isAlive, "run() must return after an interrupt")
        return InterruptOutcome(failure.get(), (finishedAt.get() - interruptedAt.get()) / 1_000_000)
    }

    private fun readerThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "bounded-process-reader" }.toSet()
}
