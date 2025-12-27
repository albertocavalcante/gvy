package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.config.WorkerDescriptorConfig
import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange
import org.slf4j.LoggerFactory

object WorkerRouterFactory {
    private val logger = LoggerFactory.getLogger(WorkerRouterFactory::class.java)

    fun fromConfig(config: ServerConfiguration): WorkerRouter {
        val seenIds = mutableSetOf<String>()
        val descriptors = config.workerDescriptors
            .mapNotNull { toDescriptor(it) }
            .filter { descriptor ->
                if (seenIds.add(descriptor.id)) {
                    true
                } else {
                    logger.warn("Duplicate worker id '{}' configured; ignoring entry", descriptor.id)
                    false
                }
            }
        if (descriptors.isEmpty()) {
            return WorkerRouter(defaultWorkerDescriptors())
        }
        return WorkerRouter(descriptors)
    }

    private fun toDescriptor(config: WorkerDescriptorConfig): WorkerDescriptor? {
        val minVersion = parseVersion(config.id, "minVersion", config.minVersion) ?: return null
        val maxVersion = if (config.maxVersion != null) {
            parseVersion(config.id, "maxVersion", config.maxVersion) ?: return null
        } else {
            null
        }
        if (maxVersion != null && maxVersion < minVersion) {
            logger.warn(
                "Invalid worker descriptor range for {}: minVersion {} > maxVersion {}",
                config.id,
                minVersion.raw,
                maxVersion.raw,
            )
            return null
        }
        val features = parseFeatures(config.id, config.features)
        val connector = parseConnector(config.id, config.connector) ?: return null

        return WorkerDescriptor(
            id = config.id,
            supportedRange = GroovyVersionRange(minVersion, maxVersion),
            capabilities = WorkerCapabilities(features),
            connector = connector,
        )
    }

    private fun parseVersion(workerId: String, field: String, raw: String): GroovyVersion? {
        val parsed = GroovyVersion.parse(raw)
        if (parsed == null) {
            logger.warn("Invalid worker descriptor {} for {}: '{}'", field, workerId, raw)
        }
        return parsed
    }

    private fun parseFeatures(workerId: String, raw: Set<String>): Set<WorkerFeature> {
        if (raw.isEmpty()) return emptySet()
        val features = mutableSetOf<WorkerFeature>()
        raw.forEach { name ->
            val feature = WorkerFeature.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (feature == null) {
                logger.warn("Unknown worker feature '{}' for {}", name, workerId)
            } else {
                features.add(feature)
            }
        }
        return features
    }

    private fun parseConnector(workerId: String, raw: String): WorkerConnector? = when (raw.lowercase()) {
        "in-process", "inprocess", "in_process" -> WorkerConnector.InProcess
        else -> {
            logger.warn("Unsupported worker connector '{}' for {}", raw, workerId)
            null
        }
    }
}
