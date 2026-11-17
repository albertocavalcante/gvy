// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShutdownWaitersTest {
    /** Holds tasks until the test releases them, so the caller/run interleaving is forced. */
    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun server(executor: Executor) = GroovyLanguageServer(exitProcess = {}, shutdownExecutor = executor)

    @Test
    fun `a caller cancelling before the run starts does not suppress cleanup or other callers`() {
        val executor = QueuedExecutor()
        val server = server(executor)
        val a = server.shutdown()
        val b = server.shutdown()
        assertNotSame(a, b)

        a.cancel(true)
        assertEquals(1, executor.tasks.size, "cleanup stays queued")
        assertFalse(b.isDone)

        executor.runAll()

        assertNull(b.get(30, TimeUnit.SECONDS))
        assertTrue(a.isCancelled)
    }

    @Test
    fun `a cancelled caller does not complete another early, and later callers still see the outcome`() {
        val executor = QueuedExecutor()
        val server = server(executor)
        val a = server.shutdown()
        val b = server.shutdown()

        a.cancel(true)
        assertFalse(b.isDone, "an uncancelled observer must not complete before the run does")

        executor.runAll()
        assertNull(b.get(30, TimeUnit.SECONDS))
        assertNull(server.shutdown().get(30, TimeUnit.SECONDS))
    }

    @Test
    fun `a rejected run fails every caller with the same cause, including later ones`() {
        val failure = IllegalStateException("executor closed")
        val server = server { throw failure }
        val first = server.shutdown()
        val second = server.shutdown()

        for (future in listOf(first, second, server.shutdown())) {
            val e = assertFailsWith<ExecutionException> { future.get(30, TimeUnit.SECONDS) }
            assertIs<IllegalStateException>(e.cause)
        }
        assertTrue(server.shutdownRequested)
    }

    @Test
    fun `a second call after completion returns a consistent completed result and runs once`() {
        val executor = QueuedExecutor()
        val server = server(executor)
        val first = server.shutdown()
        executor.runAll()
        assertNull(first.get(30, TimeUnit.SECONDS))

        val second = server.shutdown()

        assertTrue(second.isDone)
        assertNull(second.get())
        assertTrue(executor.tasks.isEmpty(), "cleanup must not be queued twice")
    }
}
