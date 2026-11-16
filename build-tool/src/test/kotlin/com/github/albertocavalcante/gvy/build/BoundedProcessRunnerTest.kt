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
}
