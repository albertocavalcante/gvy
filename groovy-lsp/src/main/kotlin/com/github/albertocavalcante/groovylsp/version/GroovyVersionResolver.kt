package com.github.albertocavalcante.groovylsp.version

import groovy.lang.GroovySystem
import org.slf4j.LoggerFactory
import java.nio.file.Path

enum class GroovyVersionSource {
    OVERRIDE,
    DEPENDENCY,
    RUNTIME,
}

data class GroovyVersionInfo(val version: GroovyVersion, val source: GroovyVersionSource)

class GroovyVersionResolver(private val runtimeVersionProvider: () -> String = { GroovySystem.getVersion() }) {
    private val logger = LoggerFactory.getLogger(GroovyVersionResolver::class.java)

    fun resolve(dependencies: List<Path>, overrideVersion: String?): GroovyVersionInfo {
        parseOverride(overrideVersion)?.let { version ->
            return GroovyVersionInfo(version, GroovyVersionSource.OVERRIDE)
        }

        val dependencyVersion = resolveFromDependencies(dependencies)
        if (dependencyVersion != null) {
            return GroovyVersionInfo(dependencyVersion, GroovyVersionSource.DEPENDENCY)
        }

        val runtimeVersion = GroovyVersion.parse(runtimeVersionProvider())
            ?: GroovyVersion.parse(DEFAULT_RUNTIME_FALLBACK)
            ?: error("Failed to parse runtime Groovy version")
        return GroovyVersionInfo(runtimeVersion, GroovyVersionSource.RUNTIME)
    }

    private fun parseOverride(overrideVersion: String?): GroovyVersion? {
        if (overrideVersion.isNullOrBlank()) return null
        val parsed = GroovyVersion.parse(overrideVersion)
        if (parsed == null) {
            logger.warn("Invalid groovy.language.version override '{}', ignoring", overrideVersion)
        }
        return parsed
    }

    private fun resolveFromDependencies(dependencies: List<Path>): GroovyVersion? {
        val versions = dependencies.mapNotNull { path ->
            val fileName = path.fileName?.toString() ?: return@mapNotNull null
            extractGroovyVersion(fileName)
        }
        return versions.maxOrNull()
    }

    private fun extractGroovyVersion(fileName: String): GroovyVersion? {
        val baseName = fileName.removeSuffix(".jar")
        val match = GROOVY_JAR_PATTERN.find(baseName) ?: return null
        val rawVersion = match.groupValues[1]
        val sanitized = rawVersion.removeSuffix("-indy")
        return GroovyVersion.parse(sanitized)
    }
}

private const val DEFAULT_RUNTIME_FALLBACK = "4.0.0"
private val GROOVY_JAR_PATTERN = Regex("""^groovy(?:-all)?-([0-9][0-9A-Za-z.\-+]*)$""")
