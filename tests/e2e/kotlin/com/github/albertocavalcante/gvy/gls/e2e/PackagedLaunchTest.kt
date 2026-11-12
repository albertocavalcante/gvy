// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class PackagedLaunchTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `verified preparation copies owned bytes and removes its staging directory`() {
        val original = jar(properties = cleanProperties())
        val expected = digest(original)
        val launch = launch(original, expected)
        val staging = Files.createDirectory(tempDir.resolve("staging"))

        lateinit var staged: Path
        launch.prepare(stagingParent = staging).use { prepared ->
            staged = prepared.jar
            assertThat(prepared.jar).isNotEqualTo(original)
            assertThat(digest(prepared.jar)).isEqualTo(expected)
            assertThat(prepared.verification.artifact).isEqualTo(VerificationState.Verified)
            assertThat(prepared.verification.source).isEqualTo(VerificationState.Verified)
            assertThat(prepared.verification.expectedDigest).isEqualTo(expected)
            assertThat(prepared.verification.observedDigest).isEqualTo(expected)
            assertThat(prepared.verification.expectedSource).isEqualTo(SourceStamp(COMMIT))
            assertThat(prepared.verification.observedSource).isEqualTo(SourceStamp(COMMIT))
            Files.writeString(original, "changed after verification")
            assertThat(digest(prepared.jar)).isEqualTo(expected)
        }
        assertThat(staged.parent).doesNotExist()
    }

    @Test
    fun `empty expected clean dirty hash is canonicalized to absent`() {
        val artifact = jar(cleanProperties())
        val configured = launch(artifact, digest(artifact)).copy(expectedSource = SourceStamp(COMMIT, false, ""))
        configured.prepare().use { prepared ->
            assertThat(prepared.verification.source).isEqualTo(VerificationState.Verified)
            assertThat(prepared.verification.expectedSource).isEqualTo(SourceStamp(COMMIT))
            assertThat(prepared.verification.observedSource).isEqualTo(SourceStamp(COMMIT))
        }
    }

    @Test
    fun `same source stamp does not make different bytes match the expected digest`() {
        val original = jar(properties = cleanProperties())
        val other = jar(properties = cleanProperties(), suffix = "other")
        val failure = failedPreparation(launch(other, digest(original)))

        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactDigestMismatch)
        assertThat(failure.verification.artifact).isEqualTo(VerificationState.Rejected)
        assertThat(failure.verification.source).isEqualTo(VerificationState.NotChecked)
        assertThat(failure.verification.expectedDigest).isEqualTo(digest(original))
        assertThat(failure.verification.observedDigest).isEqualTo(digest(other))
        assertThat(failure.verification.observedSource).isNull()
    }

    @Test
    fun `unknown malformed duplicate and incomplete source stamps fail without observed source claims`() {
        val badStamps = listOf(
            "gitSha=unknown\ngitDirty=false\ngitDirtyHash=\n",
            "gitSha=$COMMIT\ngitDirty=maybe\ngitDirtyHash=\n",
            "gitSha=$COMMIT\ngitDirtyHash=\n",
            "gitSha=$COMMIT\ngitDirty=false\ngitDirtyHash=abcdef\n",
            "gitSha=$COMMIT\ngitDirty=false\ngitDirty=false\ngitDirtyHash=\n",
        )
        badStamps.forEachIndexed { index, properties ->
            val artifact = jar(properties, suffix = index.toString())
            val expected = digest(artifact)
            val failure = failedPreparation(launch(artifact, expected), index.toString())
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
            assertThat(failure.verification.artifact).isEqualTo(VerificationState.Verified)
            assertThat(failure.verification.source).isEqualTo(VerificationState.Rejected)
            assertThat(failure.verification.expectedSource).isEqualTo(SourceStamp(COMMIT))
            assertThat(failure.verification.observedDigest).isEqualTo(expected)
            assertThat(failure.verification.observedSource).isNull()
        }
    }

    @Test
    fun `different valid clean source is recorded and rejected`() {
        val otherCommit = "b".repeat(40)
        val artifact = jar("gitSha=$otherCommit\ngitDirty=false\ngitDirtyHash=\n")
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.SourceMismatch)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Rejected)
        assertThat(failure.verification.expectedSource).isEqualTo(SourceStamp(COMMIT))
        assertThat(failure.verification.observedSource).isEqualTo(SourceStamp(otherCommit))
    }

    @Test
    fun `invalid expected digest and commit are rejected before copy`() {
        val artifact = jar(cleanProperties())
        val launch = launch(artifact, digest(artifact))
        listOf(launch.copy(expectedSha256 = "secret"), launch.copy(expectedSource = SourceStamp("secret")))
            .forEachIndexed { index, invalid ->
                val failure = failedPreparation(invalid, index.toString())
                assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidConfiguration)
                assertThat(failure.verification.artifact).isEqualTo(VerificationState.NotChecked)
                assertThat(failure.message).doesNotContain("secret")
                assertThat(failure.verification.toString()).doesNotContain("secret")
            }
    }

    private fun failedPreparation(
        launch: PackagedLaunch,
        suffix: String = "",
        limits: PreparationLimits = PreparationLimits(),
    ): PackagedLaunchException {
        val staging = Files.createDirectory(tempDir.resolve("failed-staging$suffix"))
        val failure =
            assertThrows<PackagedLaunchException> { launch.prepare(stagingParent = staging, limits = limits).close() }
        Files.list(staging).use { assertThat(it.count()).isZero() }
        return failure
    }

    @Test
    fun `manifest external classpath is rejected even with a matching digest and source`() {
        val artifact = jar(cleanProperties(), classPath = "outside.jar")
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ExternalClassPath)
    }

    @Test
    fun `duplicate provenance entry is rejected`() {
        val artifact = jar(cleanProperties(), duplicate = true)
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Rejected)
    }

    @Test
    fun `missing provenance entry is rejected`() {
        val artifact = jar(cleanProperties(), includeProperties = false)
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Rejected)
    }

    @Test
    fun `oversized embedded provenance fails closed`() {
        val artifact = jar(cleanProperties() + "padding=${"x".repeat(1_000_000)}\n")
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Rejected)
    }

    @Test
    fun `requested dirty source is explicitly unsupported`() {
        val artifact = jar(cleanProperties())
        val dirty = launch(artifact, digest(artifact)).copy(expectedSource = SourceStamp(COMMIT, true, "abcdef"))
        assertThatThrownBy { dirty.prepare().close() }
            .isInstanceOf(PackagedLaunchException::class.java)
            .extracting("reason").isEqualTo(PackagedLaunchFailure.UnsupportedDirtySource)
    }

    @Test
    fun `dirty observed stamp is explicitly unsupported`() {
        val artifact = jar("gitSha=$COMMIT\ngitDirty=true\ngitDirtyHash=abcdef\n")
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.UnsupportedDirtySource)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Unsupported)
        assertThat(failure.verification.observedSource).isEqualTo(SourceStamp(COMMIT, true, null))
    }

    @Test
    fun `entry and artifact limits fail closed and leave no staged files`() {
        val artifact = jar(cleanProperties(), extraEntries = 3)
        val input = launch(artifact, digest(artifact))
        val entryFailure = failedPreparation(input, "entries", PreparationLimits(maxEntries = 2))
        assertThat(entryFailure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
        val sizeFailure = failedPreparation(input, "bytes", PreparationLimits(maxArtifactBytes = 8))
        assertThat(sizeFailure.reason).isEqualTo(PackagedLaunchFailure.ArtifactTooLarge)
    }

    @Test
    fun `metadata byte limit rejects oversized provenance without leaking staging`() {
        val artifact = jar(cleanProperties())
        val failure = failedPreparation(
            launch(artifact, digest(artifact)),
            "metadata",
            PreparationLimits(maxMetadataBytes = 8),
        )
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
    }

    @Test
    fun `case insensitive external classpath and duplicate manifest entries are rejected`() {
        val classPath = jar(
            cleanProperties(),
            suffix = "classpath",
            rawManifest = "Manifest-Version: 1.0\r\ncLaSs-PaTh: outside.jar\r\n\r\n",
        )
        val duplicate = jar(cleanProperties(), suffix = "manifest", duplicateManifest = true)
        val external = failedPreparation(launch(classPath, digest(classPath)), "classpath")
        val ambiguous = failedPreparation(launch(duplicate, digest(duplicate)), "manifest")
        assertThat(external.reason).isEqualTo(PackagedLaunchFailure.ExternalClassPath)
        assertThat(ambiguous.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
    }

    @Test
    fun `missing artifact is unavailable and leaves source unchecked`() {
        val failure = failedPreparation(launch(tempDir.resolve("missing.jar"), "f".repeat(64)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactUnavailable)
        assertThat(failure.verification.artifact).isEqualTo(VerificationState.Unavailable)
        assertThat(failure.verification.source).isEqualTo(VerificationState.NotChecked)
        assertThat(failure.verification.observedDigest).isNull()
    }

    @Test
    fun `oversized and malformed manifest fail closed`() {
        val manifests = listOf(
            "Manifest-Version: 1.0\r\nLong: ${"x".repeat(1000)}\r\n\r\n",
            "Manifest-Version: 1.0\r\nNot a manifest attribute\r\n\r\n",
        )
        manifests.forEachIndexed { index, manifest ->
            val artifact = jar(cleanProperties(), suffix = "manifest-$index", rawManifest = manifest)
            val failure = failedPreparation(
                launch(artifact, digest(artifact)),
                "invalid-manifest-$index",
                PreparationLimits(maxMetadataBytes = 256),
            )
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
        }
    }

    @Test
    fun `symbolic link artifact is rejected without following its target`() {
        val artifact = jar(cleanProperties())
        val link = tempDir.resolve("link.jar")
        Files.createSymbolicLink(link, artifact)
        val failure = failedPreparation(launch(link, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactUnavailable)
        assertThat(failure.verification.artifact).isEqualTo(VerificationState.Unavailable)
    }

    @Test
    fun `duplicate manifest classpath attributes cannot erase an external dependency`() {
        val artifact = jar(
            cleanProperties(),
            rawManifest = "Manifest-Version: 1.0\r\nClass-Path: outside.jar\r\nClass-Path: \r\n\r\n",
        )
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
    }

    @Test
    fun `impractical metadata limit is rejected before staging`() {
        val artifact = jar(cleanProperties())
        val failure = failedPreparation(
            launch(artifact, digest(artifact)),
            "limit-overflow",
            PreparationLimits(maxMetadataBytes = Int.MAX_VALUE),
        )
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidConfiguration)
        assertThat(failure.verification.artifact).isEqualTo(VerificationState.NotChecked)
    }

    @Test
    fun `malformed ZIP and escaped properties fail safely without staged files`() {
        val brokenZip = Files.writeString(tempDir.resolve("broken.jar"), "not a zip")
        val brokenProperties = jar("gitSha=$COMMIT\ngitDirty=false\ngitDirtyHash=\ninvalid=\\uZZZZ\n")
        listOf(brokenZip, brokenProperties).forEachIndexed { index, artifact ->
            val failure = failedPreparation(launch(artifact, digest(artifact)), "malformed-$index")
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidMetadata)
            assertThat(failure.message).doesNotContain(tempDir.toString())
        }
    }

    @Test
    fun `post start verification rejects a staged artifact grown beyond the configured limit`() {
        val artifact = jar(cleanProperties())
        val limit = Files.size(artifact) + 1
        launch(
            artifact,
            digest(artifact),
        ).prepare(limits = PreparationLimits(maxArtifactBytes = limit)).use { prepared ->
            Files.write(prepared.jar, ByteArray((limit + 1).toInt()))
            val failure = assertThrows<PackagedLaunchException> { prepared.verifyAfterStart() }
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactTooLarge)
            assertThat(failure.verification.artifact).isEqualTo(VerificationState.Rejected)
        }
    }

    @Test
    fun `post start verification refuses a staged symbolic link without following it`() {
        val artifact = jar(cleanProperties())
        launch(artifact, digest(artifact)).prepare().use { prepared ->
            Files.delete(prepared.jar)
            Files.createSymbolicLink(prepared.jar, artifact)
            val failure = assertThrows<PackagedLaunchException> { prepared.verifyAfterStart() }
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactUnavailable)
            assertThat(failure.verification.artifact).isEqualTo(VerificationState.Unavailable)
        }
    }

    @Test
    fun `relative Java executable is rejected before staging`() {
        val artifact = jar(cleanProperties())
        val workingDirectory = Path.of("").toAbsolutePath()
        val executable = Files.createTempFile(workingDirectory, "gvy-java-test-", ".exe")
        try {
            assertThat(executable.toFile().setExecutable(true)).isTrue()
            assertThat(Files.isExecutable(executable)).isTrue()
            val input = launch(artifact, digest(artifact)).copy(javaExecutable = executable.fileName)
            val failure = failedPreparation(input)
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidConfiguration)
            assertThat(failure.verification.artifact).isEqualTo(VerificationState.NotChecked)
        } finally {
            Files.deleteIfExists(executable)
        }
    }

    @Test
    fun `staging cleanup failure preserves safe primary and safe suppression`() {
        val artifact = jar(cleanProperties())
        val parent = Files.createDirectory(tempDir.resolve("cleanup-parent"))
        var created: Path? = null
        try {
            val failure = assertThrows<PackagedLaunchException> {
                launch(artifact, digest(artifact)).prepare(stagingParent = parent, afterStagingCreated = { directory ->
                    created = directory
                    Files.writeString(directory.resolve("test-owned-child"), "fixture")
                    throw java.io.IOException("secret-canary")
                }).close()
            }
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactUnavailable)
            assertThat(failure.cause).isNull()
            assertThat(failure.suppressed).hasSize(1)
            assertThat(failure.suppressed.single().message).isEqualTo("Staging cleanup failed")
            assertThat(failure.stackTraceToString()).doesNotContain("secret-canary", parent.toString())
            assertThat(created).isNotNull()
        } finally {
            created?.let { directory ->
                Files.deleteIfExists(directory.resolve("test-owned-child"))
                Files.deleteIfExists(directory)
            }
        }
        Files.list(parent).use { assertThat(it.count()).isZero() }
    }

    @Test
    fun `preparation preserves cancellation and removes the owned stage`() {
        val artifact = jar(cleanProperties())
        val parent = Files.createDirectory(tempDir.resolve("cancel-stage"))
        val control = CancellationException("local cancellation")
        assertThatThrownBy {
            launch(artifact, digest(artifact)).prepare(stagingParent = parent, afterStagingCreated = { throw control })
        }.isSameAs(control)
        Files.list(parent).use { assertThat(it.count()).isZero() }
    }

    @Test
    fun `preparation preserves interruption and restores its flag after cleanup`() {
        val artifact = jar(cleanProperties())
        val parent = Files.createDirectory(tempDir.resolve("interrupt-stage"))
        val control = InterruptedException("local interruption")
        try {
            assertThatThrownBy {
                launch(artifact, digest(artifact)).prepare(stagingParent = parent, afterStagingCreated = {
                    throw control
                })
            }.isSameAs(control)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            Files.list(parent).use { assertThat(it.count()).isZero() }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `socket timeout remains a sanitized IO failure without setting interrupt`() {
        val artifact = jar(cleanProperties())
        val parent = Files.createDirectory(tempDir.resolve("timeout-stage"))
        val failure = assertThrows<PackagedLaunchException> {
            launch(artifact, digest(artifact)).prepare(stagingParent = parent, afterStagingCreated = {
                throw SocketTimeoutException("secret-canary")
            })
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactUnavailable)
        assertThat(failure.message).doesNotContain("secret-canary")
        assertThat(Thread.currentThread().isInterrupted).isFalse()
        Files.list(parent).use { assertThat(it.count()).isZero() }
    }

    @Test
    fun `preparation preserves fatal failure and removes owned stage`() {
        val artifact = jar(cleanProperties())
        val parent = Files.createDirectory(tempDir.resolve("fatal-stage"))
        val fatal = AssertionError("local fatal")
        assertThatThrownBy {
            launch(artifact, digest(artifact)).prepare(stagingParent = parent, afterStagingCreated = { throw fatal })
        }.isSameAs(fatal)
        Files.list(parent).use { assertThat(it.count()).isZero() }
    }

    @Test
    fun `prepared close preserves local control and fatal failures`() {
        val artifact = jar(cleanProperties())
        val controls = listOf(
            CancellationException("local cancellation"),
            InterruptedException("local interrupt"),
            AssertionError("local fatal"),
        )
        controls.forEach { control ->
            val prepared = launch(artifact, digest(artifact)).prepare(closeStaging = { throw control })
            try {
                assertThatThrownBy { prepared.close() }.isSameAs(control)
                assertThat(Thread.currentThread().isInterrupted).isEqualTo(control is InterruptedException)
            } finally {
                Thread.interrupted()
                Files.deleteIfExists(prepared.jar)
                Files.deleteIfExists(prepared.jar.parent)
            }
        }
    }

    @Test
    fun `dirty expected source does not retain arbitrary hash content`() {
        val artifact = jar(cleanProperties())
        val canary = "secret-canary-".repeat(500)
        val input = launch(artifact, digest(artifact)).copy(expectedSource = SourceStamp(COMMIT, true, canary))
        val failure = failedPreparation(input)
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.UnsupportedDirtySource)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Unsupported)
        assertThat(failure.verification.expectedSource?.dirtyHash).isNull()
        assertThat(failure.verification.expectedSource).isEqualTo(SourceStamp(COMMIT, true, null))
        assertThat(failure.verification.toString()).doesNotContain("secret-canary")
        assertThat(failure.stackTraceToString()).doesNotContain("secret-canary")
        assertThat(failure.cause).isNull()
    }

    @Test
    fun `dirty embedded source does not retain arbitrary hash content`() {
        val canary = "secret-canary-".repeat(500)
        val artifact = jar("gitSha=$COMMIT\ngitDirty=true\ngitDirtyHash=$canary\n")
        val failure = failedPreparation(launch(artifact, digest(artifact)))
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.UnsupportedDirtySource)
        assertThat(failure.verification.source).isEqualTo(VerificationState.Unsupported)
        assertThat(failure.verification.observedSource?.dirtyHash).isNull()
        assertThat(failure.verification.observedSource).isEqualTo(SourceStamp(COMMIT, true, null))
        assertThat(failure.verification.toString()).doesNotContain("secret-canary")
        assertThat(failure.stackTraceToString()).doesNotContain("secret-canary")
        assertThat(failure.cause).isNull()
    }

    private fun launch(artifact: Path, sha: String) = PackagedLaunch(
        javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        ),
        jar = artifact,
        expectedSha256 = sha,
        expectedSource = SourceStamp(COMMIT),
    )

    private fun jar(
        properties: String,
        suffix: String = "",
        classPath: String? = null,
        duplicate: Boolean = false,
        includeProperties: Boolean = true,
        rawManifest: String? = null,
        duplicateManifest: Boolean = false,
        extraEntries: Int = 0,
    ): Path {
        val path = tempDir.resolve("server$suffix.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            classPath?.let { mainAttributes[Attributes.Name.CLASS_PATH] = it }
        }
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            if (rawManifest != null) jar.write(rawManifest.toByteArray()) else manifest.write(jar)
            jar.closeEntry()
            if (duplicateManifest) {
                jar.putNextEntry(JarEntry("META-INF/MANIFEST.MG"))
                manifest.write(jar)
                jar.closeEntry()
            }
            repeat(extraEntries) { index ->
                jar.putNextEntry(JarEntry("extra-$index"))
                jar.closeEntry()
            }
            if (includeProperties) {
                jar.putNextEntry(JarEntry("version.properties"))
                jar.write(properties.toByteArray())
                jar.closeEntry()
            }
            if (duplicate) {
                jar.putNextEntry(JarEntry("version.propertiez"))
                jar.write(properties.toByteArray())
                jar.closeEntry()
            }
            jar.putNextEntry(JarEntry("fixture"))
            jar.write(suffix.toByteArray())
            jar.closeEntry()
        }
        if (duplicate || duplicateManifest) {
            val bytes = Files.readAllBytes(path)
            val alternate = (if (duplicate) "version.propertiez" else "META-INF/MANIFEST.MG").toByteArray()
            val canonical = (if (duplicate) "version.properties" else "META-INF/MANIFEST.MF").toByteArray()
            for (index in 0..bytes.size - alternate.size) {
                if (alternate.indices.all { bytes[index + it] == alternate[it] }) {
                    canonical.copyInto(bytes, index)
                }
            }
            Files.write(path, bytes)
        }
        return path
    }

    private fun digest(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun cleanProperties() = "gitSha=$COMMIT\ngitDirty=false\ngitDirtyHash=\n"

    private companion object {
        val COMMIT = "a".repeat(40)
    }
}
