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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
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
        val failure = AtomicReference<Throwable>()
        val runner = Thread {
            try {
                BoundedProcessRunner.run(listOf("sh", "-c", "echo started; sleep 60"), tempDir, 60.seconds)
            } catch (e: InterruptedException) {
                failure.set(e)
            }
        }
        runner.start()
        Thread.sleep(1_000)
        runner.interrupt()
        runner.join(30_000)

        assertFalse(runner.isAlive)
        assertTrue(failure.get() is InterruptedException)
        val liveReaders = Thread.getAllStackTraces().keys.count { it.name == "bounded-process-reader" && it.isAlive }
        assertEquals(0, liveReaders, "reader thread must be joined before run() rethrows")
    }
}
