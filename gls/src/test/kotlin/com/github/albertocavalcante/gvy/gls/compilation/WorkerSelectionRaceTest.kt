// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Two worker selections racing (didChangeConfiguration on the listener thread, the dependency callback on Default)
 * leave the worker the service reports and the session that parses in step.
 */
class WorkerSelectionRaceTest {
    @Test
    fun `racing selections leave the reported worker and the parsing session in step`() {
        val sessions = CopyOnWriteArrayList<CountingSession>() // the default session first, then one per worker
        val buildingFirst = CountDownLatch(1)
        val release = CountDownLatch(1)
        val compilation = GroovyCompilationService(
            workerSessionDecorator = { delegate ->
                val session = CountingSession(delegate)
                sessions += session
                // The first worker's session is held while it is built, so the second selection runs meanwhile.
                if (sessions.size == FIRST_WORKER_SESSION) {
                    buildingFirst.countDown()
                    check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the first selection was never released" }
                }
                session
            },
        )
        val first = worker("first")
        val second = worker("second")
        try {
            val selectingFirst = FutureTask<Boolean> { compilation.updateSelectedWorker(first) }
            Thread(selectingFirst, "select-first").apply {
                isDaemon = true
                start()
            }
            assertTrue(buildingFirst.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first selection never started")

            assertTrue(compilation.updateSelectedWorker(second))
            release.countDown()
            assertTrue(selectingFirst.get(WAIT_SECONDS, TimeUnit.SECONDS))

            val sessionOf = mapOf(first to sessions[FIRST_WORKER_SESSION - 1], second to sessions[FIRST_WORKER_SESSION])
            val reported = assertNotNull(compilation.getSelectedWorker())
            runBlocking { compilation.compile(URI.create("file:///race/Probe.groovy"), "class Probe {}") }
            assertSame(
                sessionOf.getValue(reported),
                sessions.single { it.parses.get() > 0 },
                "the service reports ${reported.id}, so its session must be the one that parses",
            )
        } finally {
            release.countDown()
            compilation.shutdown()
        }
    }

    private class CountingSession(private val delegate: WorkerSession) : WorkerSession {
        val parses = AtomicInteger()

        override fun parse(request: ParseRequest): ParseResult =
            delegate.parse(request).also { parses.incrementAndGet() }
    }

    private fun worker(id: String) = WorkerDescriptor(
        id = id,
        supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("4.0.0")),
        capabilities = WorkerCapabilities(),
        connector = WorkerConnector.InProcess,
    )

    private companion object {
        const val WAIT_SECONDS = 10L

        /** Position of the first worker's session among the decorated ones: the default session is decorated first. */
        const val FIRST_WORKER_SESSION = 2
    }
}
