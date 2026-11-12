// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import org.eclipse.lsp4j.InitializeParams
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParentProcessWatchdogTest {

    private class FakeLiveness(private val observable: Boolean = true) : ProcessLiveness {
        val watched = mutableListOf<Long>()
        val future = CompletableFuture<Void>()
        override fun exitFuture(pid: Long): CompletableFuture<*>? {
            watched += pid
            return if (observable) future else null
        }
    }

    @Test
    fun `parent exit triggers the exit callback once`() {
        val liveness = FakeLiveness()
        var exits = 0
        ParentProcessWatchdog(liveness) { exits++ }.watch(42)
        assertEquals(listOf(42L), liveness.watched)
        assertEquals(0, exits)

        liveness.future.complete(null)

        assertEquals(1, exits)
    }

    @Test
    fun `null process id is not watched`() {
        val liveness = FakeLiveness()
        ParentProcessWatchdog(liveness) { error("must not exit") }.watch(null)
        assertTrue(liveness.watched.isEmpty())
    }

    @Test
    fun `unobservable parent never exits`() {
        val liveness = FakeLiveness(observable = false)
        ParentProcessWatchdog(liveness) { error("must not exit") }.watch(7)
        assertEquals(listOf(7L), liveness.watched)
    }

    @Test
    fun `server initialize watches processId and exits with code 1 when parent dies`() {
        val liveness = FakeLiveness()
        val exitCode = CompletableFuture<Int>()
        val latch = CountDownLatch(1)
        val server = GroovyLanguageServer(processLiveness = liveness, exitProcess = {
            exitCode.complete(it)
            latch.countDown()
        })

        server.initialize(InitializeParams().apply { processId = 1234 }).get()
        assertEquals(listOf(1234L), liveness.watched)

        liveness.future.complete(null)

        assertTrue(latch.await(30, TimeUnit.SECONDS), "server should exit after parent death")
        assertEquals(1, exitCode.get())
    }

    @Test
    fun `the server's own pid is unobservable, so initialize does not fail`() {
        val ownPid = ProcessHandle.current().pid()

        assertNull(SystemProcessLiveness.exitFuture(ownPid))

        var exited = false
        ParentProcessWatchdog(SystemProcessLiveness) { exited = true }.watch(ownPid.toInt())
        assertFalse(exited)
    }

    private fun exitCodes(): Pair<CompletableFuture<Int>, (Int) -> Unit> {
        val codes = CompletableFuture<Int>()
        return codes to { code: Int -> codes.complete(code) }
    }

    @Test
    fun `parent death after shutdown exits 0 and shares the one shutdown run`() {
        val liveness = FakeLiveness()
        val (code, exit) = exitCodes()
        val server = GroovyLanguageServer(processLiveness = liveness, exitProcess = exit)
        server.initialize(InitializeParams().apply { processId = 1234 }).get()

        val first = server.shutdown()
        first.get(30, TimeUnit.SECONDS)
        liveness.future.complete(null)

        assertEquals(0, code.get(30, TimeUnit.SECONDS))
        assertTrue(first === server.shutdown(), "shutdown must not run a second time")
    }

    @Test
    fun `parent death without shutdown shuts down once then exits 1`() {
        val liveness = FakeLiveness()
        val (code, exit) = exitCodes()
        val server = GroovyLanguageServer(processLiveness = liveness, exitProcess = exit)
        server.initialize(InitializeParams().apply { processId = 1234 }).get()
        assertFalse(server.shutdownRequested)

        liveness.future.complete(null)

        assertEquals(1, code.get(30, TimeUnit.SECONDS))
        assertTrue(server.shutdownRequested)
        assertTrue(server.shutdown().isDone, "shutdown ran before exit")
    }

    @Test
    fun `exit after shutdown exits 0`() {
        val (code, exit) = exitCodes()
        val server = GroovyLanguageServer(exitProcess = exit)
        server.shutdown().get(30, TimeUnit.SECONDS)

        server.exit()

        assertEquals(0, code.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `exit without shutdown exits 1`() {
        val (code, exit) = exitCodes()
        val server = GroovyLanguageServer(exitProcess = exit)

        server.exit()

        assertEquals(1, code.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `already dead parent does not block initialize`() {
        val liveness = FakeLiveness().also { it.future.complete(null) }
        val release = CountDownLatch(1)
        val server = GroovyLanguageServer(processLiveness = liveness, exitProcess = {
            release.await(30, TimeUnit.SECONDS)
        })

        try {
            server.initialize(InitializeParams().apply { processId = 1234 }).get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
        }
    }
}
