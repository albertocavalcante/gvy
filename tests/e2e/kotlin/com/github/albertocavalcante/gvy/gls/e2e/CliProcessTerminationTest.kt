package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CliProcessTerminationTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `interruption during CLI cleanup retries exit confirmation and restores the signal`() {
        val process = InterruptedTerminationProcess(confirmExit = true)

        val (failure, interrupted) = execute(process)

        assertThat(failure).isInstanceOf(TimeoutException::class.java)
        assertThat(failure.suppressed).isEmpty()
        assertThat(process.waitCalls).isEqualTo(EXPECTED_WAIT_CALLS)
        assertThat(process.isAlive).isFalse()
        assertThat(process.cleanupBudgets).hasSize(2)
        assertThat(process.cleanupBudgets.last()).isLessThanOrEqualTo(process.cleanupBudgets.first())
        assertThat(interrupted).isTrue()
    }

    @Test
    fun `interrupted cleanup reports unconfirmed termination when the retry cannot confirm exit`() {
        val process = InterruptedTerminationProcess(confirmExit = false)

        val (failure, interrupted) = execute(process)

        assertThat(failure).isInstanceOf(TimeoutException::class.java)
        assertThat(failure.suppressed).hasSize(1)
        assertThat(failure.suppressed.single()).isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("termination is not confirmed")
        assertThat(process.waitCalls).isEqualTo(EXPECTED_WAIT_CALLS)
        assertThat(process.isAlive).isTrue()
        assertThat(process.destroyRequested).isTrue()
        assertThat(interrupted).isTrue()
    }

    private fun execute(process: InterruptedTerminationProcess): Pair<Throwable, Boolean> {
        lateinit var command: ProcessBuilder
        val executor = CliCommandStepExecutor { builder ->
            command = builder
            process
        }
        val context = ScenarioContext(
            definition = ScenarioDefinition(Scenario("cli-cleanup-interruption", steps = emptyList()), "scenario.yaml"),
            session = mockk(),
            workspace = ScenarioWorkspace(workspace),
            jsonPathConfig = Configuration.defaultConfiguration(),
        )
        try {
            val failure = checkNotNull(catchThrowable { executor.execute(ScenarioStep.CliCommand("tool"), context) })
            assertThat(command.redirectOutput()).isEqualTo(ProcessBuilder.Redirect.PIPE)
            assertThat(command.redirectError()).isEqualTo(ProcessBuilder.Redirect.PIPE)
            assertThat(process.closedStreams).isEqualTo(STREAM_COUNT)
            return failure to Thread.currentThread().isInterrupted
        } finally {
            Thread.interrupted()
        }
    }

    private class InterruptedTerminationProcess(private val confirmExit: Boolean) : Process() {
        var waitCalls = 0
        var closedStreams = 0
        var destroyRequested = false
        val cleanupBudgets = mutableListOf<Long>()
        private var alive = true
        private val stdout = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                closedStreams++
            }
        }
        private val stderr = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                closedStreams++
            }
        }
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() {
                closedStreams++
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = stdin
        override fun waitFor(): Int = error("Use the bounded process wait")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls++
            if (waitCalls == 1) return false // Trigger the original CLI step timeout.
            cleanupBudgets.add(unit.toNanos(timeout))
            if (waitCalls == 2) throw InterruptedException("interrupted while confirming CLI exit")
            alive = !confirmExit
            return confirmExit
        }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            destroyRequested = true // The OS has accepted termination but the process has not exited yet.
        }
        override fun destroyForcibly(): Process = apply { destroy() }
    }

    private companion object {
        const val EXPECTED_WAIT_CALLS = 3
        const val STREAM_COUNT = 3
    }
}
