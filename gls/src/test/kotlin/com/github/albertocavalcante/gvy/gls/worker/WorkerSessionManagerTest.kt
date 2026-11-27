// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.gvy.gls.test.TEST_GROOVY_VERSION
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

        manager.select(TEST_GROOVY_VERSION, descriptor("worker"))
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

        manager.select(TEST_GROOVY_VERSION, descriptor("worker"))
        manager.select(TEST_GROOVY_VERSION, null)
        val result = manager.parse(request)

        assertSame(defaultResult, result)
        assertSame(request, defaultSession.lastRequest)
    }

    @Test
    fun `select reports a change only when the selected worker differs`() {
        val manager = WorkerSessionManager(StubSession(mockk()), sessionFactory = { StubSession(mockk()) })

        assertTrue(manager.select(TEST_GROOVY_VERSION, descriptor("worker")))
        assertFalse(manager.select(TEST_GROOVY_VERSION, descriptor("worker")))
        assertEquals(descriptor("worker"), manager.selectedWorker)
        assertTrue(manager.select(TEST_GROOVY_VERSION, null))
        assertNull(manager.selectedWorker)
        assertFalse(manager.select(TEST_GROOVY_VERSION, null))
    }

    @Test
    fun `a version is selected together with its worker`() {
        val manager = WorkerSessionManager(StubSession(mockk()), sessionFactory = { StubSession(mockk()) })
        val groovy3 = GroovyVersionInfo(parseGroovyVersion("3.0.0"), GroovyVersionSource.RUNTIME)

        assertTrue(manager.select(groovy3, descriptor("worker")))
        assertEquals(groovy3, manager.selectedVersion)
        assertTrue(manager.select(TEST_GROOVY_VERSION, descriptor("other")))

        assertEquals(TEST_GROOVY_VERSION, manager.selectedVersion)
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
