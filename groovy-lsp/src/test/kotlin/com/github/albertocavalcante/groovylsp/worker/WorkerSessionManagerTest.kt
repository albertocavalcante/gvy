package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.test.parseGroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertSame

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
