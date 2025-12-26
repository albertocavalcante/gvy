package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange

sealed interface WorkerConnector {
    data object InProcess : WorkerConnector
}

data class WorkerDescriptor(
    val id: String,
    val supportedRange: GroovyVersionRange,
    val capabilities: WorkerCapabilities,
    val connector: WorkerConnector,
)

class WorkerSelector(descriptors: List<WorkerDescriptor>) {
    private val workers: List<WorkerDescriptor>

    init {
        val ids = descriptors.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Worker ids must be unique; duplicates: ${duplicates.sorted()}" }
        workers = descriptors.toList()
    }

    fun select(requestedVersion: GroovyVersion, requiredFeatures: Set<WorkerFeature> = emptySet()): WorkerDescriptor? {
        val candidates = workers.filter { descriptor ->
            descriptor.supportedRange.contains(requestedVersion) &&
                descriptor.capabilities.features.containsAll(requiredFeatures)
        }

        return candidates.maxWithOrNull(COMPARATOR)
    }
}

private val COMPARATOR = Comparator<WorkerDescriptor> { left, right ->
    val minCompare = left.supportedRange.minInclusive.compareTo(right.supportedRange.minInclusive)
    if (minCompare != 0) return@Comparator minCompare

    val leftMax = left.supportedRange.maxInclusive
    val rightMax = right.supportedRange.maxInclusive
    val leftBounded = leftMax != null
    val rightBounded = rightMax != null
    if (leftBounded != rightBounded) return@Comparator leftBounded.compareTo(rightBounded)

    if (leftBounded && rightBounded && leftMax != rightMax) {
        return@Comparator rightMax.compareTo(leftMax)
    }

    left.id.compareTo(right.id)
}
