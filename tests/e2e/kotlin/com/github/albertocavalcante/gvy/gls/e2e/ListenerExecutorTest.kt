package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class ListenerExecutorTest {
    @Test
    fun `close waits for the owned worker thread to exit after executor termination`() {
        val taskFinished = CountDownLatch(1)
        val allowExit = CountDownLatch(1)
        lateinit var worker: Thread
        val factory = ThreadFactory { task ->
            Thread {
                task.run()
                taskFinished.countDown()
                // Deliberately retain the OS thread after ThreadPoolExecutor reports TERMINATED.
                var released = false
                while (!released) {
                    try {
                        allowExit.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // shutdownNow may leave the worker interrupted before this gate.
                    }
                }
            }.apply {
                isDaemon = true
                worker = this
            }
        }
        val executor = ListenerExecutor("thread-exit", factory)
        executor.service.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)
        val interruptRestored = AtomicBoolean()
        val closed = FutureTask<Unit> {
            executor.close()
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }
        val closer = Thread(closed).apply { isDaemon = true }

        try {
            closer.start()
            assertThat(taskFinished.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(executor.service.isTerminated).isTrue()
            assertThat(worker.isAlive).isTrue()
            closer.interrupt()
            assertThatThrownBy { closed.get(EARLY_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            allowExit.countDown()
            closed.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertThat(worker.isAlive).isFalse()
            assertThat(interruptRestored).isTrue()
        } finally {
            allowExit.countDown()
            worker.join(JOIN_TIMEOUT_MS)
            closer.join(JOIN_TIMEOUT_MS)
            executor.close()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 2L
        const val EARLY_CLOSE_WAIT_MS = 200L
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
