// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.concurrent.OnceFlag
import com.github.albertocavalcante.gvy.concurrent.SharedValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Every test spawns or interrupts a process, so a hang must fail this test by name long before Bazel's 300 s limit.
@Timeout(60)
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
        // No polling: run() itself must not return before the kill has completed.
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
                BoundedProcessRunner.run(
                    listOf("stuck"),
                    tempDir,
                    120.seconds,
                    BoundedProcessRunner.Waits(200.milliseconds, 200.milliseconds),
                ) {
                        _,
                        _,
                    ->
                    process
                }
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
        assertTrue(process.destroyed.isSet, "the process must be killed")
        assertTrue(process.streamClosed.isSet, "a reader that does not drain must have its stream closed")
        assertTrue(process.readerInterrupted.await(10, TimeUnit.SECONDS), "a blocked reader must be interrupted")
    }

    @Test
    fun `interrupt returns only after the killed process has exited`() {
        // destroyForcibly only requests the kill: the process is still alive for a moment afterwards, as on a loaded
        // machine. A caller that checks right after run() returns must already see it dead.
        val process = LingeringProcess(dyingMillis = 500)
        val failure = AtomicReference<Throwable>()
        val aliveOnReturn = AtomicReference<Boolean>()
        val runner = Thread {
            try {
                BoundedProcessRunner.run(
                    listOf("lingering"),
                    tempDir,
                    120.seconds,
                    BoundedProcessRunner.Waits(readerJoin = 200.milliseconds),
                ) {
                        _,
                        _,
                    ->
                    process
                }
            } catch (e: InterruptedException) {
                failure.set(e)
            } finally {
                aliveOnReturn.set(process.isAlive)
            }
        }
        runner.start()
        assertTrue(process.running.await(20, TimeUnit.SECONDS), "run() never started waiting for the process")
        runner.interrupt()
        runner.join(30_000)

        assertFalse(runner.isAlive, "run() must return after an interrupt")
        assertTrue(failure.get() is InterruptedException)
        assertFalse(aliveOnReturn.get(), "run() returned while the killed process was still alive")
    }

    @Test
    fun `timeout returns only after the killed process has exited`() {
        val process = LingeringProcess(dyingMillis = 500)

        val result = BoundedProcessRunner.run(
            listOf("lingering"),
            tempDir,
            100.milliseconds,
            BoundedProcessRunner.Waits(readerJoin = 200.milliseconds),
        ) {
                _,
                _,
            ->
            process
        }

        assertTrue(result.timedOut)
        assertFalse(process.isAlive, "run() returned while the killed process was still alive")
    }

    @Test
    fun `a process that never exits is reported as a survivor once the exit wait elapses`() {
        val process = LingeringProcess(dyingMillis = null)
        val started = System.nanoTime()

        val result = BoundedProcessRunner.run(
            listOf("immortal"),
            tempDir,
            100.milliseconds,
            BoundedProcessRunner.Waits(200.milliseconds, 300.milliseconds),
        ) {
                _,
                _,
            ->
            process
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(result.timedOut)
        assertEquals(listOf(LingeringProcess.PID), result.survivors)
        assertTrue(elapsedMillis < 10_000, "the exit wait must be bounded by its limit, took ${elapsedMillis}ms")
    }

    @Test
    fun `a clean kill reports no survivors`() {
        val process = LingeringProcess(dyingMillis = 50)

        val result = BoundedProcessRunner.run(
            listOf("lingering"),
            tempDir,
            100.milliseconds,
            BoundedProcessRunner.Waits(readerJoin = 200.milliseconds),
        ) {
                _,
                _,
            ->
            process
        }

        assertEquals(emptyList<Long>(), result.survivors)
    }

    @Test
    fun `run waits for a descendant that exits late`() {
        val descendant = LateHandle(dyingMillis = 600)
        val process = LingeringProcess(dyingMillis = 10, descendants = listOf(descendant))

        val result = BoundedProcessRunner.run(
            listOf("tree"),
            tempDir,
            100.milliseconds,
            BoundedProcessRunner.Waits(readerJoin = 200.milliseconds),
        ) {
                _,
                _,
            ->
            process
        }

        assertTrue(result.timedOut)
        assertFalse(descendant.isAlive, "run() returned while a killed descendant was still alive")
        assertEquals(emptyList<Long>(), result.survivors)
    }

    /** A descendant whose death is [dyingMillis] after the kill request. */
    private class LateHandle(private val dyingMillis: Long) : ProcessHandle {
        private val diesAtNanos = SharedValue(Long.MAX_VALUE)
        private val exit = CompletableFuture<ProcessHandle>()

        override fun pid(): Long = DESCENDANT_PID

        override fun isAlive(): Boolean = System.nanoTime() < diesAtNanos.value

        override fun onExit(): CompletableFuture<ProcessHandle> = exit

        override fun destroyForcibly(): Boolean {
            diesAtNanos.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(dyingMillis))
            CompletableFuture.delayedExecutor(dyingMillis, TimeUnit.MILLISECONDS).execute { exit.complete(this) }
            return true
        }

        override fun destroy(): Boolean = destroyForcibly()

        override fun supportsNormalTermination(): Boolean = false

        override fun parent(): Optional<ProcessHandle> = Optional.empty()

        override fun children(): Stream<ProcessHandle> = Stream.empty()

        override fun descendants(): Stream<ProcessHandle> = Stream.empty()

        override fun info(): ProcessHandle.Info = throw UnsupportedOperationException()

        override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())

        private companion object {
            const val DESCENDANT_PID = 4242L
        }
    }

    /**
     * A process that dies [dyingMillis] after it is asked to, like a kill that the kernel has not yet completed;
     * `null` means it never dies.
     */
    private class LingeringProcess(
        private val dyingMillis: Long?,
        private val descendants: List<ProcessHandle> = emptyList(),
    ) : Process() {
        val running = CountDownLatch(1)

        private val diesAtNanos = SharedValue(Long.MAX_VALUE)

        override fun isAlive(): Boolean = System.nanoTime() < diesAtNanos.value

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = throw UnsupportedOperationException()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            running.countDown()
            val limit = System.nanoTime() + unit.toNanos(timeout)
            while (isAlive && System.nanoTime() < limit) Thread.sleep(10)
            return !isAlive
        }

        override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else KILLED

        override fun destroy() {
            val delay = dyingMillis ?: return
            val dies = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay)
            diesAtNanos.update { current -> minOf(current, dies) }
        }

        override fun descendants(): Stream<ProcessHandle> = descendants.stream()

        override fun pid(): Long = PID

        companion object {
            const val PID = 1234L
            private const val KILLED = 137
        }
    }

    /** A process that never exits and whose output stream blocks until it is interrupted. */
    private class StuckProcess : Process() {
        val readerBlocked = CountDownLatch(1)
        val readerInterrupted = CountDownLatch(1)

        val streamClosed = OnceFlag()

        val destroyed = OnceFlag()

        private val input = object : InputStream() {
            override fun read(): Int = block()

            override fun read(b: ByteArray, off: Int, len: Int): Int = block()

            override fun close() {
                streamClosed.set()
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

        override fun pid(): Long = 1L

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
            destroyed.set()
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
