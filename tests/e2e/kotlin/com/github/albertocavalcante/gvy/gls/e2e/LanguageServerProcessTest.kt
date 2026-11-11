package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LanguageServerProcessTest {
    @Test
    fun `factory teardown destroys its process and closes all three streams without requiring exit`() {
        val process = RecordingProcess()
        val session = LanguageServerSessionFactory(startProcess = { process }).start(ServerConfig(), "process-cleanup")
        assertThat(process.isAlive).isTrue()

        session.close()
        session.close()

        assertThat(process.destroyCalls).isEqualTo(1)
        assertThat(process.waitCalls).isEqualTo(1)
        assertStreamsClosed(process)
    }

    @Test
    fun `failed launcher setup destroys its already started process and closes all streams`() {
        val failure = IOException("failed to access input during launcher setup")
        val process = RecordingProcess().also { it.inputFailure = failure }

        assertThatThrownBy {
            LanguageServerSessionFactory(startProcess = { process }).start(ServerConfig(), "failed-process-setup")
        }.isSameAs(failure)

        assertThat(process.destroyCalls).isEqualTo(1)
        assertStreamsClosed(process)
    }

    @Test
    fun `process wait failure remains primary when closing an owned stream also fails`() {
        val failure = IllegalStateException("process wait failed")
        val cleanup = IOException("output close failed")
        val process = RecordingProcess().also {
            it.waitFailure = failure
            it.output.closeFailure = cleanup
        }
        val session = LanguageServerSessionFactory(startProcess = {
            process
        }).start(ServerConfig(), "process-wait-failure")

        assertThatThrownBy { session.close() }.isSameAs(failure)

        assertThat(failure.suppressed).containsExactly(cleanup)
        assertStreamsClosed(process)
    }

    @Test
    fun `interrupted process wait restores interruption after closing every resource`() {
        val interruption = InterruptedException("process wait interrupted")
        val cleanup = IOException("output close failed")
        val process = RecordingProcess().also {
            it.waitFailure = interruption
            it.output.closeFailure = cleanup
        }
        val session = LanguageServerSessionFactory(startProcess = { process }).start(ServerConfig(), "interrupted-wait")

        try {
            assertThatThrownBy { session.close() }.isSameAs(interruption)
            assertThat(interruption.suppressed).containsExactly(cleanup)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertStreamsClosed(process)
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertStreamsClosed(process: RecordingProcess) {
        assertThat(process.input.closed.get()).isTrue()
        assertThat(process.output.closed.get()).isTrue()
        assertThat(process.error.closed.get()).isTrue()
        assertThat(process.isAlive).isFalse()
    }

    private class RecordingProcess : Process() {
        val input = RecordingInput()
        val output = RecordingOutput()
        val error = RecordingInput()
        var inputFailure: IOException? = null
        var waitFailure: Exception? = null
        var destroyCalls = 0
        var waitCalls = 0
        private var alive = true

        override fun getInputStream(): RecordingInput {
            inputFailure?.let { failure ->
                inputFailure = null
                throw failure
            }
            return input
        }

        override fun getOutputStream(): RecordingOutput = output
        override fun getErrorStream(): RecordingInput = error
        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int = 0
        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls++
            waitFailure?.let { throw it }
            return !alive
        }

        override fun destroy() {
            destroyCalls++
            alive = false
        }
    }

    private class RecordingInput : ByteArrayInputStream(byteArrayOf()) {
        val closed = AtomicBoolean()

        override fun close() {
            closed.set(true)
            super.close()
        }
    }

    private class RecordingOutput : ByteArrayOutputStream() {
        val closed = AtomicBoolean()
        var closeFailure: IOException? = null

        override fun close() {
            closed.set(true)
            closeFailure?.let { throw it }
            super.close()
        }
    }
}
