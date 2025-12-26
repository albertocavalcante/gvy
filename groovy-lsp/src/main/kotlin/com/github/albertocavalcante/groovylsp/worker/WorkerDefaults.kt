package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange

private const val DEFAULT_WORKER_ID = "in-process-default"
private const val DEFAULT_GROOVY_MIN_VERSION = "1.0.0"

internal fun defaultWorkerDescriptors(): List<WorkerDescriptor> {
    val minVersion = parseGroovyVersion(DEFAULT_GROOVY_MIN_VERSION)
    return listOf(
        WorkerDescriptor(
            id = DEFAULT_WORKER_ID,
            supportedRange = GroovyVersionRange(minInclusive = minVersion),
            capabilities = WorkerCapabilities(features = setOf(WorkerFeature.AST, WorkerFeature.SYMBOLS)),
            connector = WorkerConnector.InProcess,
        ),
    )
}

private fun parseGroovyVersion(raw: String): GroovyVersion =
    requireNotNull(GroovyVersion.parse(raw)) { "Failed to parse Groovy version: $raw" }
