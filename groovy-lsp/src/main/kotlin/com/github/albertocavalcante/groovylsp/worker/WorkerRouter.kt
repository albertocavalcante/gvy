package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersionInfo

class WorkerRouter(descriptors: List<WorkerDescriptor>) {
    private val selector = WorkerSelector(descriptors)

    fun select(
        groovyVersionInfo: GroovyVersionInfo,
        requiredFeatures: Set<WorkerFeature> = emptySet(),
    ): WorkerDescriptor? = selector.select(
        requestedVersion = groovyVersionInfo.version,
        requiredFeatures = requiredFeatures,
    )
}
