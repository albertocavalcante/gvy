// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionSource
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Two selections racing (didChangeConfiguration on the listener thread, the dependency callback on Default) leave
 * the Groovy version, the worker the service reports and the session that parses in step.
 */
class WorkerSelectionRaceTest {
    private val first = worker("first")
    private val second = worker("second")

    @Test
    fun `racing selections keep the reported version, worker and parsing session in step`() {
        val firstVersion = version("3.0.0")
        val secondVersion = version("4.0.0")
        RaceFixture().use { fixture ->
            fixture.race(
                { updateGroovySelection(firstVersion, first) },
                { updateGroovySelection(secondVersion, second) },
            )

            val reported = assertNotNull(fixture.compilation.getSelectedWorker())
            val versionOf = mapOf(first to firstVersion, second to secondVersion)
            assertEquals(versionOf.getValue(reported), fixture.compilation.getGroovyVersionInfo())
            assertSame(
                fixture.sessionOf(reported),
                fixture.parsingSession(),
                "the service reports ${reported.id}, so its session must be the one that parses",
            )
        }
    }

    /**
     * A compilation service whose first worker session is held while it is built, so a second selection can run in
     * between. Sessions are decorated in the order they are built: the default session, then the first worker's,
     * then the second's.
     */
    private inner class RaceFixture : Closeable {
        private val sessions = CopyOnWriteArrayList<CountingSession>()
        private val buildingFirst = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val compilation = GroovyCompilationService(
            workerSessionDecorator = { delegate ->
                val session = CountingSession(delegate)
                sessions += session
                if (sessions.size == FIRST_WORKER_SESSION) {
                    buildingFirst.countDown()
                    check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the first selection was never released" }
                }
                session
            },
        )

        /** Runs [selectFirst] on its own thread, runs [selectSecond] to completion while it is held, then ends it. */
        fun race(
            selectFirst: GroovyCompilationService.() -> Boolean,
            selectSecond: GroovyCompilationService.() -> Boolean,
        ) {
            val selectingFirst = FutureTask<Boolean> { compilation.selectFirst() }
            Thread(selectingFirst, "select-first").apply {
                isDaemon = true
                start()
            }
            assertTrue(buildingFirst.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first selection never started")
            assertTrue(compilation.selectSecond())
            release.countDown()
            assertTrue(selectingFirst.get(WAIT_SECONDS, TimeUnit.SECONDS))
        }

        fun sessionOf(worker: WorkerDescriptor): CountingSession =
            sessions[if (worker == first) FIRST_WORKER_SESSION - 1 else FIRST_WORKER_SESSION]

        /** The one session that parses a compile now. */
        fun parsingSession(): CountingSession {
            runBlocking { compilation.compile(URI.create("file:///race/Probe.groovy"), "class Probe {}") }
            return sessions.single { it.parses.get() > 0 }
        }

        override fun close() {
            release.countDown()
            compilation.shutdown()
        }
    }

    private class CountingSession(private val delegate: WorkerSession) : WorkerSession {
        val parses = AtomicInteger()

        override fun parse(request: ParseRequest): ParseResult =
            delegate.parse(request).also { parses.incrementAndGet() }
    }

    private fun version(raw: String) = GroovyVersionInfo(parseGroovyVersion(raw), GroovyVersionSource.RUNTIME)

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
