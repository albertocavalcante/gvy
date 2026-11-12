// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.BuildInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile

data class SourceStamp(val commit: String, val dirty: Boolean = false, val dirtyHash: String? = null)
data class JvmSettings(val maxHeapMiB: Int = 1024, val initialHeapMiB: Int? = null)
enum class JavaEnvironmentPolicy { Remove, Reject }
enum class SessionEvidencePolicy { Default, Private }
enum class VerificationState { NotChecked, Verified, Rejected, Unsupported, Unavailable }
enum class PackagedLaunchFailure {
    InvalidConfiguration,
    UnsupportedDirtySource,
    ArtifactUnavailable,
    ArtifactTooLarge,
    ArtifactDigestMismatch,
    InvalidMetadata,
    SourceMismatch,
    ExternalClassPath,
    EnvironmentRejected,
    ProcessStartFailed,
}

/** Dirty hashes are never retained in verification evidence; dirty builds are unsupported. */
data class LaunchVerification(
    val artifact: VerificationState = VerificationState.NotChecked,
    val source: VerificationState = VerificationState.NotChecked,
    val javaEnvironment: VerificationState = VerificationState.NotChecked,
    val expectedDigest: String? = null,
    val expectedSource: SourceStamp? = null,
    val observedDigest: String? = null,
    val observedSource: SourceStamp? = null,
)

data class RuntimeEvidence(
    val before: LaunchVerification,
    val afterStart: LaunchVerification,
    val requestedJvmSettings: JvmSettings,
    val javaRuntimeVersion: String? = null,
    val effectiveMaxHeapBytes: Long? = null,
)

data class StderrEvidence(val bytesObserved: Long, val eof: Boolean, val failed: Boolean)

class PackagedLaunchException(val reason: PackagedLaunchFailure, val verification: LaunchVerification) :
    IllegalStateException("Packaged server launch verification failed")

data class PackagedLaunch(
    val javaExecutable: Path,
    val jar: Path,
    val expectedSha256: String,
    val expectedSource: SourceStamp,
    val jvmSettings: JvmSettings = JvmSettings(),
    val javaEnvironmentPolicy: JavaEnvironmentPolicy = JavaEnvironmentPolicy.Remove,
) {
    internal fun prepare(
        stagingParent: Path? = null,
        limits: PreparationLimits = PreparationLimits(),
        afterStagingCreated: (Path) -> Unit = {},
        closeStaging: (Path) -> Unit = ::removeStaging,
    ): PreparedLaunch {
        val validDigest = expectedSha256.matches(Regex("[0-9a-f]{64}"))
        val validSource = expectedSource.commit.matches(Regex("[0-9a-f]{40}")) &&
            (expectedSource.dirty || expectedSource.dirtyHash.isNullOrEmpty())
        val canonicalSource = expectedSource.copy(dirtyHash = null)
        var verification = LaunchVerification(
            expectedDigest = expectedSha256.takeIf { validDigest },
            expectedSource = canonicalSource.takeIf { validSource },
        )
        fun fail(reason: PackagedLaunchFailure): Nothing = throw PackagedLaunchException(reason, verification)
        val initialHeap = jvmSettings.initialHeapMiB
        val validHeap = jvmSettings.maxHeapMiB > 0 &&
            (initialHeap == null || (initialHeap > 0 && initialHeap <= jvmSettings.maxHeapMiB))
        val validLimits = limits.maxArtifactBytes > 0 &&
            limits.maxMetadataBytes > 0 &&
            limits.maxMetadataBytes <= 1_048_576 &&
            limits.maxEntries > 0
        if (!validDigest || !validSource || !validHeap || !validLimits) {
            fail(PackagedLaunchFailure.InvalidConfiguration)
        }
        val usableJava = javaExecutable.isAbsolute &&
            Files.isRegularFile(javaExecutable) &&
            Files.isExecutable(javaExecutable)
        if (!usableJava) fail(PackagedLaunchFailure.InvalidConfiguration)
        if (expectedSource.dirty) {
            verification = verification.copy(source = VerificationState.Unsupported)
            fail(PackagedLaunchFailure.UnsupportedDirtySource)
        }
        var directory: Path? = null
        try {
            directory = if (stagingParent == null) {
                Files.createTempDirectory("gvy-packaged-")
            } else {
                Files.createTempDirectory(stagingParent, "gvy-packaged-")
            }
            afterStagingCreated(directory)
            val staged = directory.resolve("server.jar")
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) throw IOException("unavailable")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(jar, LinkOption.NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(staged).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > limits.maxArtifactBytes) {
                            verification = verification.copy(artifact = VerificationState.Rejected)
                            fail(PackagedLaunchFailure.ArtifactTooLarge)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val observed = digest.digest().joinToString("") { "%02x".format(it) }
            verification = verification.copy(observedDigest = observed)
            if (observed != expectedSha256) {
                verification = verification.copy(artifact = VerificationState.Rejected)
                fail(PackagedLaunchFailure.ArtifactDigestMismatch)
            }
            verification = verification.copy(artifact = VerificationState.Verified)
            val metadata = readMetadata(staged, limits)
            if (metadata.externalClassPath) {
                verification = verification.copy(source = VerificationState.Rejected)
                fail(PackagedLaunchFailure.ExternalClassPath)
            }
            val observedSource = if (metadata.source.dirty) {
                metadata.source.copy(dirtyHash = null)
            } else {
                metadata.source
            }
            verification = verification.copy(observedSource = observedSource)
            if (metadata.source.dirty) {
                verification = verification.copy(source = VerificationState.Unsupported)
                fail(PackagedLaunchFailure.UnsupportedDirtySource)
            }
            if (metadata.source != canonicalSource) {
                verification = verification.copy(source = VerificationState.Rejected)
                fail(PackagedLaunchFailure.SourceMismatch)
            }
            verification = verification.copy(source = VerificationState.Verified)
            return PreparedLaunch(staged, verification, minOf(size, limits.maxArtifactBytes), closeStaging)
        } catch (failure: Throwable) {
            // Local control exceptions retain identity; callers must never export their contents.
            val primary = if (localControl(failure) != null) {
                failure
            } else {
                when (failure) {
                    is PackagedLaunchException -> failure
                    is InvalidMetadata -> PackagedLaunchException(
                        PackagedLaunchFailure.InvalidMetadata,
                        verification.copy(source = VerificationState.Rejected),
                    )
                    else -> PackagedLaunchException(
                        PackagedLaunchFailure.ArtifactUnavailable,
                        verification.copy(artifact = VerificationState.Unavailable),
                    )
                }
            }
            cleanupFailedPreparation(directory, primary)
            rethrowLocalControl(primary)
            throw primary
        }
    }
}

private fun cleanupFailedPreparation(directory: Path?, failure: Throwable) {
    if (directory == null) return
    try {
        removeStaging(directory)
    } catch (cleanup: Throwable) {
        if (cleanup !== failure) {
            val retained = if (localControl(cleanup) !=
                null
            ) {
                cleanup
            } else {
                IllegalStateException("Staging cleanup failed")
            }
            failure.addSuppressed(retained)
        }
    }
}

internal class PreparedLaunch(
    val jar: Path,
    val verification: LaunchVerification,
    private val maxArtifactBytes: Long,
    private val closeStaging: (Path) -> Unit = ::removeStaging,
) : AutoCloseable {
    fun verifyAfterStart(): LaunchVerification {
        val digest = try {
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) throw IOException("unavailable")
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(jar, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > maxArtifactBytes - size) {
                        throw PackagedLaunchException(
                            PackagedLaunchFailure.ArtifactTooLarge,
                            verification.copy(
                                artifact = VerificationState.Rejected,
                                observedDigest = null,
                                source = VerificationState.NotChecked,
                                observedSource = null,
                            ),
                        )
                    }
                    size += count
                    hash.update(buffer, 0, count)
                }
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        } catch (failure: IOException) {
            rethrowLocalControl(failure)
            throw PackagedLaunchException(
                PackagedLaunchFailure.ArtifactUnavailable,
                verification.copy(
                    artifact = VerificationState.Unavailable,
                    observedDigest = null,
                    source = VerificationState.NotChecked,
                    observedSource = null,
                ),
            )
        }
        if (digest != verification.expectedDigest) {
            throw PackagedLaunchException(
                PackagedLaunchFailure.ArtifactDigestMismatch,
                verification.copy(
                    artifact = VerificationState.Rejected,
                    observedDigest = digest,
                    source = VerificationState.NotChecked,
                    observedSource = null,
                ),
            )
        }
        return verification.copy(observedDigest = digest)
    }

    override fun close() {
        try {
            closeStaging(jar.parent)
        } catch (failure: Throwable) {
            rethrowLocalControl(failure)
            throw IllegalStateException("Staging cleanup failed")
        }
    }
}

private fun removeStaging(directory: Path) {
    Files.deleteIfExists(directory.resolve("server.jar"))
    Files.deleteIfExists(directory)
}

private class InvalidMetadata : Exception()
private data class PackageMetadata(val source: SourceStamp, val externalClassPath: Boolean)

private fun readMetadata(jar: Path, limits: PreparationLimits): PackageMetadata {
    try {
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries()
            var count = 0
            var versionBytes: ByteArray? = null
            var manifestBytes: ByteArray? = null
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > limits.maxEntries) throw InvalidMetadata()
                if (entry.name == "version.properties" || entry.name.equals("META-INF/MANIFEST.MF", true)) {
                    if (entry.isDirectory) throw InvalidMetadata()
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(limits.maxMetadataBytes + 1) }
                    if (bytes.size > limits.maxMetadataBytes) throw InvalidMetadata()
                    if (entry.name == "version.properties") {
                        if (versionBytes != null) throw InvalidMetadata()
                        versionBytes = bytes
                    } else {
                        if (manifestBytes != null) throw InvalidMetadata()
                        manifestBytes = bytes
                    }
                }
            }
            val properties = UniqueProperties()
            properties.load(ByteArrayInputStream(versionBytes ?: throw InvalidMetadata()))
            val info = BuildInfo.fromProperties(properties)
            val dirty = info.gitDirty ?: throw InvalidMetadata()
            if (!info.gitSha.matches(Regex("[0-9a-f]{40}")) ||
                !properties.containsKey("gitDirtyHash") ||
                (!dirty && info.gitDirtyHash.isNotEmpty()) ||
                (dirty && info.gitDirtyHash.isEmpty())
            ) {
                throw InvalidMetadata()
            }
            val manifestData = manifestBytes ?: throw InvalidMetadata()
            validateManifestAttributes(manifestData)
            val manifest = Manifest(ByteArrayInputStream(manifestData))
            val external = !manifest.mainAttributes.getValue(Attributes.Name.CLASS_PATH).isNullOrBlank()
            return PackageMetadata(SourceStamp(info.gitSha, dirty, info.gitDirtyHash.ifEmpty { null }), external)
        }
    } catch (failure: InvalidMetadata) {
        throw failure
    } catch (_: IllegalArgumentException) {
        throw InvalidMetadata()
    } catch (failure: IOException) {
        rethrowLocalControl(failure)
        throw InvalidMetadata()
    }
}

private class UniqueProperties : Properties() {
    override fun put(key: Any, value: Any): Any? {
        if (containsKey(key)) throw InvalidMetadata()
        return super.put(key, value)
    }
}

internal data class PreparationLimits(
    val maxArtifactBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxMetadataBytes: Int = 64 * 1024,
    val maxEntries: Int = 100_000,
)

/** The source directory must be trusted against concurrent path replacement. */
private fun validateManifestAttributes(bytes: ByteArray) {
    val sections = mutableListOf<MutableList<ByteArrayOutputStream>>(mutableListOf())
    var start = 0
    for (end in 0..bytes.size) {
        if (end < bytes.size && bytes[end] != '\n'.code.toByte()) continue
        val lineEnd = if (end > start && bytes[end - 1] == '\r'.code.toByte()) end - 1 else end
        val current = bytes.copyOfRange(start, lineEnd)
        start = end + 1
        when {
            current.isEmpty() -> sections.add(mutableListOf())
            current[0] == ' '.code.toByte() -> {
                val section = sections.last()
                if (section.isEmpty()) throw InvalidMetadata()
                section.last().write(current, 1, current.size - 1)
            }
            else -> sections.last().add(ByteArrayOutputStream().apply { write(current) })
        }
    }
    val entryNames = mutableMapOf<String, MutableSet<Attributes.Name>>()
    sections.forEachIndexed { index, section ->
        if (index > 0 && section.isEmpty()) return@forEachIndexed
        val names: MutableSet<Attributes.Name> = if (index == 0) {
            mutableSetOf()
        } else {
            val header = section.first().toByteArray().toString(Charsets.UTF_8)
            if (!header.startsWith("Name: ", ignoreCase = true)) throw InvalidMetadata()
            entryNames.getOrPut(header.substring(6)) { mutableSetOf() }
        }
        val attributes = if (index == 0) section else section.drop(1)
        for (attribute in attributes) {
            val text = attribute.toByteArray().toString(Charsets.UTF_8)
            val separator = text.indexOf(':')
            if (separator <= 0 || !names.add(Attributes.Name(text.substring(0, separator)))) throw InvalidMetadata()
        }
    }
}

/** Local control and fatal signals retain identity and may contain private data; never serialize them as evidence. */
internal fun rethrowLocalControl(failure: Throwable) {
    val graph = controlGraph(failure)
    if (graph.interruption != null) Thread.currentThread().interrupt()
    val control = graph.fatal ?: graph.interruption ?: graph.cancellation ?: return
    if (graph.hasOrdinaryFailure && control.suppressed.none { it.message == "Session cleanup failed" }) {
        control.addSuppressed(IllegalStateException("Session cleanup failed"))
    }
    throw control
}

private fun isInterruption(failure: Throwable): Boolean = failure is InterruptedException ||
    failure is ClosedByInterruptException ||
    (failure is InterruptedIOException && failure !is SocketTimeoutException)

private data class ControlGraph(
    val fatal: Throwable?,
    val interruption: Throwable?,
    val cancellation: Throwable?,
    val hasOrdinaryFailure: Boolean,
)

/** Prefer fatal errors, then interruption, then cancellation when cleanup produces multiple signals. */
private fun localControl(failure: Throwable): Throwable? {
    val graph = controlGraph(failure)
    return graph.fatal ?: graph.interruption ?: graph.cancellation
}

private fun controlGraph(failure: Throwable): ControlGraph {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.add(failure)
    var fatal: Throwable? = null
    var interruption: Throwable? = null
    var cancellation: Throwable? = null
    var ordinary = false
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        when {
            current is Error -> if (fatal == null) fatal = current
            isInterruption(current) -> if (interruption == null) interruption = current
            current is CancellationException -> if (cancellation == null) cancellation = current
            else -> ordinary = true
        }
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return ControlGraph(fatal, interruption, cancellation, ordinary)
}
