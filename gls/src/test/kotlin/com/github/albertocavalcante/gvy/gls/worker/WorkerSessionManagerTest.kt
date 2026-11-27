// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionSource
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkerSessionManagerTest {

    @Test
    fun `select uses worker session when set`() {
        val defaultResult = mockk<ParseResult>()
        val workerResult = mockk<ParseResult>()
        val defaultSession = StubSession(defaultResult)
        val workerSession = StubSession(workerResult)
        val manager = WorkerSessionManager(
            defaultSession = defaultSession,
            sessionFactory = { workerSession },
        )
        val request = ParseRequest(URI.create("file:///test.groovy"), "class Test {}")

        manager.select(descriptor("worker"))
        val result = manager.parse(request)

        assertSame(workerResult, result)
        assertSame(request, workerSession.lastRequest)
    }

    @Test
    fun `select falls back to default session when cleared`() {
        val defaultResult = mockk<ParseResult>()
        val workerResult = mockk<ParseResult>()
        val defaultSession = StubSession(defaultResult)
        val workerSession = StubSession(workerResult)
        val manager = WorkerSessionManager(
            defaultSession = defaultSession,
            sessionFactory = { workerSession },
        )
        val request = ParseRequest(URI.create("file:///test.groovy"), "class Test {}")

        manager.select(descriptor("worker"))
        manager.select(null)
        val result = manager.parse(request)

        assertSame(defaultResult, result)
        assertSame(request, defaultSession.lastRequest)
    }

    @Test
    fun `select reports a change only when the selected worker differs`() {
        val manager = WorkerSessionManager(StubSession(mockk()), sessionFactory = { StubSession(mockk()) })

        assertTrue(manager.select(descriptor("worker")))
        assertFalse(manager.select(descriptor("worker")))
        assertEquals(descriptor("worker"), manager.selectedWorker)
        assertTrue(manager.select(null))
        assertNull(manager.selectedWorker)
        assertFalse(manager.select(null))
    }

    @Test
    fun `a version selects with its worker and a worker alone keeps the version`() {
        val manager = WorkerSessionManager(StubSession(mockk()), sessionFactory = { StubSession(mockk()) })
        val version = GroovyVersionInfo(parseGroovyVersion("3.0.0"), GroovyVersionSource.RUNTIME)

        assertTrue(manager.select(version, descriptor("worker")))
        assertEquals(version, manager.selectedVersion)
        assertTrue(manager.select(descriptor("other")))

        assertEquals(version, manager.selectedVersion)
        assertEquals(descriptor("other"), manager.selectedWorker)
    }

    private class StubSession(private val result: ParseResult) : WorkerSession {
        var lastRequest: ParseRequest? = null

        override fun parse(request: ParseRequest): ParseResult {
            lastRequest = request
            return result
        }
    }

    private fun descriptor(id: String): WorkerDescriptor = WorkerDescriptor(
        id = id,
        supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("4.0.0")),
        capabilities = WorkerCapabilities(),
        connector = WorkerConnector.InProcess,
    )
}
