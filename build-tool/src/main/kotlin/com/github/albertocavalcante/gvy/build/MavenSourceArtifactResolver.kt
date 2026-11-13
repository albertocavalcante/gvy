// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.maven.AetherSessionFactory
import com.github.albertocavalcante.gvy.build.maven.DefaultRepositoryProvider
import com.github.albertocavalcante.gvy.build.maven.RepositoryProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.ArtifactNotFoundException
import org.eclipse.aether.transfer.TransferCancelledException
import org.eclipse.aether.transfer.TransferEvent
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val SHA256_HEX_LENGTH = 64
private const val KIBIBYTE = 1_024
private const val MEBIBYTE = 1_048_576L
private const val DEFAULT_VERIFIED_CACHE_MEBIBYTES = 512L
private const val CACHE_DOWNLOAD_RESERVATION_PATHS = 512
private const val CACHE_OFFLINE_RESERVATION_PATHS = 32
private const val CACHE_OFFLINE_RESERVATION_BYTES = 64L * KIBIBYTE

/**
 * Maven-based implementation of SourceArtifactResolver using Eclipse Aether.
 *
 * Downloads and caches source JARs from configured Maven repositories.
 * Unlike the previous implementation, this version:
 * - Uses shared AetherSessionFactory (no code duplication)
 * - Accepts repositories from the project's build configuration
 * - Supports Nexus/Artifactory when configured in pom.xml
 */
class MavenSourceArtifactResolver(
    override val cacheDir: Path = defaultSourceArtifactCacheRoot(),
    private val repositoryProvider: RepositoryProvider = DefaultRepositoryProvider(),
    private val verifiedCacheBytes: Long = DEFAULT_VERIFIED_CACHE_MEBIBYTES * MEBIBYTE,
) : SourceArtifactResolver,
    VerifiedSourceArtifactResolver {

    private val logger = KotlinLogging.logger {}

    /** Repositories are tried in order; source metadata and bytes must come from the repository proving this binary. */
    override suspend fun resolveVerifiedSourceArtifact(
        request: VerifiedSourceArtifactRequest,
    ): VerifiedSourceArtifactResult = withTimeoutOrNull(SOURCE_REQUEST_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) {
            if (
                !supportsVerifiedSourceCoordinates(request.coordinates) ||
                !SHA256.matches(request.selectedBinarySha256)
            ) {
                return@runInterruptible VerifiedSourceArtifactResult.UnsupportedArtifact
            }
            try {
                resolveVerified(request, defaultVerifiedSourceCacheRoot(cacheDir))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SourceResolverCacheLimit) {
                VerifiedSourceArtifactResult.LimitExceeded
            } catch (_: java.io.IOException) {
                VerifiedSourceArtifactResult.Failed
            }
        }
    } ?: VerifiedSourceArtifactResult.Failed

    private fun resolveVerified(request: VerifiedSourceArtifactRequest, root: Path): VerifiedSourceArtifactResult {
        var binaryMismatchObserved = false
        for (repository in repositoryProvider.getRepositories()) {
            val key = repositoryCacheKey(request, repository)
            if (!request.allowDownload && !Files.isDirectory(root.resolve(key))) continue
            val reservationBytes =
                if (request.allowDownload) SOURCE_CACHE_RESERVATION_BYTES else CACHE_OFFLINE_RESERVATION_BYTES
            val reservationPaths =
                if (request.allowDownload) CACHE_DOWNLOAD_RESERVATION_PATHS else CACHE_OFFLINE_RESERVATION_PATHS
            val result = withSourceResolverCapacity(
                root = root,
                maximumBytes = verifiedCacheBytes,
                reservationBytes = reservationBytes,
                reservationPaths = reservationPaths,
            ) { canonical ->
                resolveRepository(request, repository, canonical.resolve(key))
            }
            if (result == VerifiedSourceArtifactResult.BinaryMismatch) {
                binaryMismatchObserved = true
            } else if (result != null) {
                return result
            }
        }
        return when {
            binaryMismatchObserved -> VerifiedSourceArtifactResult.BinaryMismatch
            request.allowDownload -> VerifiedSourceArtifactResult.IntegrityUnavailable
            else -> VerifiedSourceArtifactResult.DownloadsDisabled
        }
    }

    private fun resolveRepository(
        request: VerifiedSourceArtifactRequest,
        repository: RemoteRepository,
        local: Path,
    ): VerifiedSourceArtifactResult? {
        val coordinate = request.coordinates
        val session = verifiedSession(local, request.allowDownload)
        val repositories = listOf(repository)
        return when (
            val binary = verifySelectedBinary(coordinate, request.selectedBinarySha256, repositories, session)
        ) {
            BinaryCheck.TryNextRepository -> null
            BinaryCheck.SelectedBinaryMatches -> resolveSourceArtifact(coordinate, repositories, session)
            is BinaryCheck.Decided -> binary.result
        }
    }

    private fun verifySelectedBinary(
        coordinates: ResolvedArtifactCoordinates,
        selectedBinarySha256: String,
        repositories: List<RemoteRepository>,
        session: org.eclipse.aether.RepositorySystemSession,
    ): BinaryCheck = when (val checksum = resolveChecksum(coordinates, "", "jar.sha256", repositories, session)) {
        is ChecksumFetch.Valid -> if (checksum.sha256 == selectedBinarySha256) {
            BinaryCheck.SelectedBinaryMatches
        } else {
            BinaryCheck.Decided(VerifiedSourceArtifactResult.BinaryMismatch)
        }
        ChecksumFetch.Missing -> BinaryCheck.TryNextRepository
        ChecksumFetch.Invalid -> BinaryCheck.Decided(VerifiedSourceArtifactResult.IntegrityUnavailable)
        ChecksumFetch.Failed -> BinaryCheck.Decided(VerifiedSourceArtifactResult.Failed)
    }

    private fun resolveSourceArtifact(
        coordinates: ResolvedArtifactCoordinates,
        repositories: List<RemoteRepository>,
        session: org.eclipse.aether.RepositorySystemSession,
    ): VerifiedSourceArtifactResult = when (
        val checksum = resolveChecksum(coordinates, "sources", "jar.sha256", repositories, session)
    ) {
        is ChecksumFetch.Valid -> when (
            val source = resolveArtifact(coordinates, "sources", "jar", repositories, session)
        ) {
            is ArtifactFetch.Found -> source.path.toVerifiedSourceResult(checksum.sha256)
            ArtifactFetch.Missing -> VerifiedSourceArtifactResult.NotAvailable
            ArtifactFetch.Failed -> VerifiedSourceArtifactResult.Failed
        }
        ChecksumFetch.Missing, ChecksumFetch.Invalid -> VerifiedSourceArtifactResult.IntegrityUnavailable
        ChecksumFetch.Failed -> VerifiedSourceArtifactResult.Failed
    }

    private fun Path.toVerifiedSourceResult(expectedSha256: String): VerifiedSourceArtifactResult =
        if (Files.size(this) > MAX_SOURCE_DOWNLOAD_BYTES) {
            VerifiedSourceArtifactResult.LimitExceeded
        } else {
            VerifiedSourceArtifactResult.Available(this, expectedSha256)
        }

    private fun resolveChecksum(
        coordinates: ResolvedArtifactCoordinates,
        classifier: String,
        extension: String,
        repositories: List<RemoteRepository>,
        session: org.eclipse.aether.RepositorySystemSession,
    ): ChecksumFetch = when (
        val fetched = resolveArtifact(coordinates, classifier, extension, repositories, session)
    ) {
        is ArtifactFetch.Found -> readChecksum(fetched.path)?.let(ChecksumFetch::Valid) ?: ChecksumFetch.Invalid
        ArtifactFetch.Missing -> ChecksumFetch.Missing
        ArtifactFetch.Failed -> ChecksumFetch.Failed
    }

    private fun repositoryCacheKey(request: VerifiedSourceArtifactRequest, repository: RemoteRepository): String {
        val coordinate = request.coordinates
        val identity = listOf(
            request.contextId,
            repository.id,
            repositoryLocation(repository.url),
            coordinate.group,
            coordinate.module,
            coordinate.version,
            request.selectedBinarySha256,
        ).joinToString("") { value -> value.length.toString() + ":" + value }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifiedSession(local: Path, allowDownload: Boolean): DefaultRepositorySystemSession =
        DefaultRepositorySystemSession(AetherSessionFactory.createSession(local)).apply {
            isOffline = !allowDownload
            checksumPolicy = "fail"
            updatePolicy = "always" // Explicit Retry can recover from earlier missing/auth/transport results.
            setConfigProperty("aether.checksums.algorithms", "SHA-256")
            setConfigProperty("aether.checksums.omitChecksumsForExtensions", ".asc,.sigstore,.sigstore.json,.sha256")
            setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, SOURCE_CONNECT_TIMEOUT_MS)
            setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, SOURCE_READ_TIMEOUT_MS)
            setConfigProperty("aether.transport.http.retryHandler.count", 0)
            transferListener = object : AbstractTransferListener() {
                override fun transferProgressed(event: TransferEvent) {
                    val maximumBytes = if (event.resource.resourceName.endsWith(".sha256")) {
                        MAX_CHECKSUM_BYTES.toLong()
                    } else {
                        MAX_SOURCE_DOWNLOAD_BYTES
                    }
                    if (event.transferredBytes > maximumBytes) {
                        throw TransferCancelledException("Source transfer limit exceeded")
                    }
                }
            }
        }

    private fun readChecksum(path: Path): String? = Files.newInputStream(path).use { input ->
        val bytes = input.readNBytes(MAX_CHECKSUM_BYTES + 1)
        if (bytes.size > MAX_CHECKSUM_BYTES) {
            null
        } else {
            bytes.toString(Charsets.US_ASCII).trim().substringBefore(' ').lowercase().takeIf(SHA256::matches)
        }
    }

    private fun resolveArtifact(
        coordinates: ResolvedArtifactCoordinates,
        classifier: String,
        extension: String,
        repositories: List<RemoteRepository>,
        session: org.eclipse.aether.RepositorySystemSession,
    ): ArtifactFetch {
        val request = ArtifactRequest().apply {
            artifact = DefaultArtifact(
                coordinates.group,
                coordinates.module,
                classifier,
                extension,
                coordinates.version,
            )
            this.repositories = repositories
        }
        return try {
            AetherSessionFactory.repositorySystem.resolveArtifact(session, request).artifact?.file?.toPath()
                ?.let(ArtifactFetch::Found) ?: ArtifactFetch.Failed
        } catch (failure: ArtifactResolutionException) {
            val errors = failure.results.flatMap { it.exceptions }
            if (errors.isNotEmpty() && errors.all { it is ArtifactNotFoundException }) {
                ArtifactFetch.Missing
            } else {
                ArtifactFetch.Failed
            }
        }
    }

    private sealed interface ArtifactFetch {
        data class Found(val path: Path) : ArtifactFetch
        data object Missing : ArtifactFetch
        data object Failed : ArtifactFetch
    }

    private sealed interface ChecksumFetch {
        data class Valid(val sha256: String) : ChecksumFetch
        data object Missing : ChecksumFetch
        data object Invalid : ChecksumFetch
        data object Failed : ChecksumFetch
    }

    private sealed interface BinaryCheck {
        data object TryNextRepository : BinaryCheck
        data object SelectedBinaryMatches : BinaryCheck
        data class Decided(val result: VerifiedSourceArtifactResult) : BinaryCheck
    }

    private fun repositoryLocation(value: String): String {
        val uri = java.net.URI(value)
        return java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }

    override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
        resolveSourceJar(groupId, artifactId, version, repositoryProvider.getRepositories())

    /**
     * Resolve source JAR with explicit repository list.
     *
     * This overload allows callers to provide project-specific repositories,
     * enabling resolution from Nexus/Artifactory or other custom registries.
     */
    suspend fun resolveSourceJar(
        groupId: String,
        artifactId: String,
        version: String,
        repositories: List<RemoteRepository>,
    ): Path? = withContext(Dispatchers.IO) {
        logger.debug { "Resolving sources JAR: $groupId:$artifactId:$version" }

        // Check cache first
        val cachedPath = getCachePath(groupId, artifactId, version)
        if (Files.exists(cachedPath)) {
            logger.debug { "Found cached sources JAR: $cachedPath" }
            return@withContext cachedPath
        }

        @Suppress("TooGenericExceptionCaught") // Catch-all for artifact resolution errors
        try {
            val session = AetherSessionFactory.createSession(cacheDir)
            val artifact = DefaultArtifact(groupId, artifactId, "sources", "jar", version)
            val request = ArtifactRequest().apply {
                this.artifact = artifact
                this.repositories = repositories
            }

            val result = AetherSessionFactory.repositorySystem.resolveArtifact(session, request)

            if (result.isResolved) {
                val resolvedPath = result.artifact.file.toPath()
                logger.info { "Resolved sources JAR: $resolvedPath" }

                // Copy to our cache directory for consistent access
                ensureCacheDirectory(groupId, artifactId, version)
                if (resolvedPath != cachedPath && Files.notExists(cachedPath)) {
                    Files.copy(resolvedPath, cachedPath)
                }
                cachedPath
            } else {
                logger.warn { "Sources JAR not found: $groupId:$artifactId:$version" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to resolve sources JAR: $groupId:$artifactId:$version" }
            null
        }
    }

    override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean {
        val cachePath = getCachePath(groupId, artifactId, version)
        return Files.exists(cachePath)
    }

    /**
     * Build the cache path following Maven repository layout.
     */
    private fun getCachePath(groupId: String, artifactId: String, version: String): Path {
        val groupPath = groupId.replace('.', '/')
        val fileName = "$artifactId-$version-sources.jar"
        return cacheDir.resolve(groupPath).resolve(artifactId).resolve(version).resolve(fileName)
    }

    /**
     * Ensure the cache directory structure exists.
     */
    private fun ensureCacheDirectory(groupId: String, artifactId: String, version: String) {
        val cachePath = getCachePath(groupId, artifactId, version)
        val parentDir = cachePath.parent
        if (parentDir != null && Files.notExists(parentDir)) {
            Files.createDirectories(parentDir)
        }
    }

    companion object {
        private const val SOURCE_REQUEST_TIMEOUT_MS = 30_000L
        private const val SOURCE_CONNECT_TIMEOUT_MS = 5_000
        private const val SOURCE_READ_TIMEOUT_MS = 10_000
        private const val MAX_SOURCE_DOWNLOAD_BYTES = 64L * MEBIBYTE
        private const val MAX_CHECKSUM_BYTES = KIBIBYTE
        private const val SOURCE_CACHE_RESERVATION_BYTES = MAX_SOURCE_DOWNLOAD_BYTES + MEBIBYTE
        private val SHA256 = Regex("[a-f0-9]{$SHA256_HEX_LENGTH}")

        /**
         * Default cache directory: ~/.groovy-lsp/cache/sources
         */
        fun getDefaultCacheDir(): Path = defaultSourceArtifactCacheRoot()
    }
}
