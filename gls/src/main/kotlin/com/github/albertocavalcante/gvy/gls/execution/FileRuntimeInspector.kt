// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

internal data class SelectedRuntime(val jars: List<String>, val version: String?)

/** Inspects only selected runtime jars; project application dependencies remain in their own classpath. */
internal object FileRuntimeInspector {
    private const val GROOVY_CORE = "groovy/lang/GroovySystem.class"
    private const val SERVER_CLASS = "com/github/albertocavalcante/gvy/gls/GroovyLanguageServer.class"
    private const val GROOVY_RELEASE_INFO = "META-INF/groovy-release-info.properties"
    private const val MAX_JARS = 1_024
    private const val MAX_JAR_BYTES = 128L * 1_024 * 1_024
    private const val MAX_TOTAL_BYTES = 256L * 1_024 * 1_024
    private const val MAX_MANIFEST_BYTES = 64 * 1_024
    private const val MAX_VERSION_LENGTH = 64

    fun inspect(jars: List<String>?): ExecutionStep<SelectedRuntime> {
        if (jars.isNullOrEmpty()) return executionStop("Configure a valid Groovy runtime for this context.")
        if (jars.size > MAX_JARS) return executionStop("The selected runtime has too many JARs.")
        return validateSizes(jars).flatMap { observe(jars).flatMap { selectRuntime(jars, it) } }
    }

    private fun validateSizes(jars: List<String>): ExecutionStep<Unit> = try {
        val sizes = jars.map { name ->
            val path = Path.of(name)
            if (!Files.isRegularFile(path)) throw IOException("Runtime path is not a file")
            Files.size(path)
        }
        when {
            sizes.any { it > MAX_JAR_BYTES } -> executionStop("A selected runtime JAR exceeds the size limit.")
            sizes.sum() > MAX_TOTAL_BYTES -> executionStop("The selected runtime exceeds the total size limit.")
            else -> ExecutionStep.Value(Unit)
        }
    } catch (_: IOException) {
        executionStop("The selected Groovy runtime contains an invalid JAR.")
    } catch (_: IllegalArgumentException) {
        executionStop("The selected Groovy runtime contains an invalid JAR.")
    } catch (_: SecurityException) {
        executionStop("The selected Groovy runtime contains an invalid JAR.")
    }

    private fun observe(jars: List<String>): ExecutionStep<RuntimeObservation> {
        return try {
            var coreCount = 0
            var containsServer = false
            var version: String? = null
            val moduleVersions = mutableSetOf<String>()
            var conflictingMetadata = false
            var totalBytes = 0L
            for (name in jars) {
                val path = Path.of(name)
                val size = Files.size(path)
                if (size > MAX_JAR_BYTES || size > MAX_TOTAL_BYTES - totalBytes) {
                    return executionStop("The selected runtime exceeds the size limit.")
                }
                totalBytes += size
                val archive = when (val result = inspectArchive(path)) {
                    is ExecutionStep.Stop -> return result
                    is ExecutionStep.Value -> result.value
                }
                if (archive.containsServer) containsServer = true
                if (archive.isCore) {
                    coreCount++
                    val manifestVersion = archive.manifestVersion
                    val releaseVersion = archive.releaseVersion
                    conflictingMetadata = manifestVersion != null && releaseVersion != null &&
                        manifestVersion != releaseVersion
                    version = manifestVersion ?: releaseVersion
                } else {
                    archive.releaseVersion?.let(moduleVersions::add)
                }
            }
            ExecutionStep.Value(
                RuntimeObservation(coreCount, containsServer, version, moduleVersions, conflictingMetadata),
            )
        } catch (_: IOException) {
            executionStop("The selected Groovy runtime contains an invalid JAR.")
        } catch (_: IllegalArgumentException) {
            executionStop("The selected Groovy runtime contains an invalid JAR.")
        } catch (_: SecurityException) {
            executionStop("The selected Groovy runtime contains an invalid JAR.")
        }
    }

    private fun inspectArchive(path: Path): ExecutionStep<ArchiveObservation> =
        JarFile(path.toFile(), false).use { jar ->
            val release = readReleaseInfo(jar)
            if (release.tooLarge) {
                executionStop("The Groovy release metadata exceeds the size limit.")
            } else {
                archiveWithRelease(jar, release)
            }
        }

    private fun archiveWithRelease(jar: JarFile, release: ManifestObservation): ExecutionStep<ArchiveObservation> {
        val isCore = jar.getJarEntry(GROOVY_CORE) != null
        val manifest = if (isCore) readManifest(jar) else ManifestObservation(null, false)
        return if (manifest.tooLarge) {
            executionStop("The Groovy runtime manifest exceeds the size limit.")
        } else {
            ExecutionStep.Value(
                ArchiveObservation(jar.getJarEntry(SERVER_CLASS) != null, isCore, manifest.version, release.version),
            )
        }
    }

    private fun readManifest(jar: JarFile): ManifestObservation {
        val entry = jar.getJarEntry(JarFile.MANIFEST_NAME) ?: return ManifestObservation(null, false)
        if (entry.size > MAX_MANIFEST_BYTES) return ManifestObservation(null, true)
        val bytes = jar.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
        if (bytes.size > MAX_MANIFEST_BYTES) return ManifestObservation(null, true)
        val raw = Manifest(ByteArrayInputStream(bytes)).mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
        return ManifestObservation(safeVersion(raw), false)
    }

    private fun readReleaseInfo(jar: JarFile): ManifestObservation {
        val entry = jar.getJarEntry(GROOVY_RELEASE_INFO) ?: return ManifestObservation(null, false)
        if (entry.size > MAX_MANIFEST_BYTES) return ManifestObservation(null, true)
        val bytes = jar.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
        if (bytes.size > MAX_MANIFEST_BYTES) return ManifestObservation(null, true)
        val properties = Properties().apply { load(ByteArrayInputStream(bytes)) }
        return ManifestObservation(safeVersion(properties.getProperty("ImplementationVersion")), false)
    }

    private fun safeVersion(raw: String?): String? = raw?.takeIf { value ->
        value.isNotBlank() && value.length <= MAX_VERSION_LENGTH && value.all { it in ' '..'~' }
    }

    private fun selectRuntime(jars: List<String>, observation: RuntimeObservation): ExecutionStep<SelectedRuntime> =
        when {
            observation.containsServer -> executionStop("The language-server JAR cannot be used as a Groovy runtime.")
            observation.coreCount == 0 -> executionStop("The selected runtime does not contain a Groovy core.")
            observation.coreCount > 1 -> executionStop("The selected runtime contains conflicting Groovy cores.")
            observation.conflictingMetadata -> executionStop("The Groovy core has conflicting version metadata.")
            observation.version != null && observation.moduleVersions.any { it != observation.version } ->
                executionStop("A Groovy module version conflicts with the selected core version.")
            else -> ExecutionStep.Value(SelectedRuntime(jars, observation.version))
        }

    private data class ManifestObservation(val version: String?, val tooLarge: Boolean)
    private data class ArchiveObservation(
        val containsServer: Boolean,
        val isCore: Boolean,
        val manifestVersion: String?,
        val releaseVersion: String?,
    )
    private data class RuntimeObservation(
        val coreCount: Int,
        val containsServer: Boolean,
        val version: String?,
        val moduleVersions: Set<String>,
        val conflictingMetadata: Boolean,
    )
}
