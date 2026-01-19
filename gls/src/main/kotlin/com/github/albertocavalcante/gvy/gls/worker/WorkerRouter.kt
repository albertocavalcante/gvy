package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo

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
