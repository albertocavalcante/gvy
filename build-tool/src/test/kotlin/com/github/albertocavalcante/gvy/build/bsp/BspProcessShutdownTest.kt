// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.bsp

import com.github.albertocavalcante.gvy.build.BuildExecutableResolver
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BspProcessShutdownTest {

    @BeforeEach
    fun requirePosixShell() {
        assumeFalse(BuildExecutableResolver.isWindows)
    }

    @Test
    fun `descendants of a wrapper script are killed after the grace period`() {
        val process = ProcessBuilder("sh", "-c", "sleep 60 & echo \$!; wait").start()
        var child: ProcessHandle? = null
        try {
            val childPid = process.inputStream.bufferedReader().readLine().toLong()
            child = ProcessHandle.of(childPid).orElseThrow()
            assertTrue(child.isAlive)

            terminateProcessTree(process, process.descendants().toList(), graceMillis = 200)

            process.waitFor()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (child.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse(child.isAlive, "forked server must not outlive the client")
        } finally {
            child?.destroyForcibly()
            process.destroyForcibly()
        }
    }

    @Test
    fun `a process that exits within the grace period is not waited on for the full grace`() {
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        val started = System.nanoTime()

        terminateProcessTree(process, emptyList(), graceMillis = 30_000)

        assertTrue((System.nanoTime() - started) / 1_000_000 < 20_000)
        assertFalse(process.isAlive)
    }
}
