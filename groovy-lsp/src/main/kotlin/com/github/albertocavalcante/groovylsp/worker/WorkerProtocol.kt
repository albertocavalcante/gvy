package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange

object WorkerProtocol {
    const val VERSION = 1
}

enum class WorkerFeature {
    AST,
    SYMBOLS,
}

data class WorkerCapabilities(val features: Set<WorkerFeature> = emptySet())

data class WorkerHandshakeRequest(val protocolVersion: Int, val requestedGroovyVersion: GroovyVersion)

data class WorkerHandshakeResponse(
    val protocolVersion: Int,
    val workerId: String,
    val groovyRange: GroovyVersionRange,
    val capabilities: WorkerCapabilities,
) {
    fun isCompatible(request: WorkerHandshakeRequest): Boolean =
        protocolVersion == request.protocolVersion && groovyRange.contains(request.requestedGroovyVersion)
}

interface GroovyWorkerProtocol {
    fun handshake(request: WorkerHandshakeRequest): WorkerHandshakeResponse
}
