package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerHandshakeTest {

    @Test
    fun `handshake compatible when protocol matches and version in range`() {
        val request = handshakeRequest("3.0.9")
        val response = handshakeResponse(features = setOf(WorkerFeature.AST))

        assertTrue(response.isCompatible(request))
    }

    @Test
    fun `handshake rejects protocol mismatch`() {
        val request = handshakeRequest("3.0.9", protocolVersion = WorkerProtocol.VERSION + 1)
        val response = handshakeResponse()

        assertFalse(response.isCompatible(request))
    }

    @Test
    fun `handshake rejects version outside range`() {
        val request = handshakeRequest("4.2.0")
        val response = handshakeResponse()

        assertFalse(response.isCompatible(request))
    }

    private fun handshakeRequest(
        version: String,
        protocolVersion: Int = WorkerProtocol.VERSION,
    ): WorkerHandshakeRequest = WorkerHandshakeRequest(
        protocolVersion = protocolVersion,
        requestedGroovyVersion = GroovyVersion.parse(version)!!,
    )

    private fun handshakeResponse(
        rangeMin: String = "2.5.0",
        rangeMax: String = "4.0.0",
        protocolVersion: Int = WorkerProtocol.VERSION,
        features: Set<WorkerFeature> = emptySet(),
    ): WorkerHandshakeResponse = WorkerHandshakeResponse(
        protocolVersion = protocolVersion,
        workerId = "worker-1",
        groovyRange = GroovyVersionRange(
            GroovyVersion.parse(rangeMin)!!,
            GroovyVersion.parse(rangeMax)!!,
        ),
        capabilities = WorkerCapabilities(features = features),
    )
}
