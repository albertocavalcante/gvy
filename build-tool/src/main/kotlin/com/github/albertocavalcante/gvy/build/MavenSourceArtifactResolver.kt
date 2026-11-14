// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.maven.AetherSessionFactory
import com.github.albertocavalcante.gvy.build.maven.DefaultRepositoryProvider
import com.github.albertocavalcante.gvy.build.maven.RepositoryProvider
import com.github.albertocavalcante.gvy.build.maven.SourceTransferCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.resolution.VersionRequest
import org.eclipse.aether.resolution.VersionResolutionException
import org.eclipse.aether.resolution.VersionResult
import org.eclipse.aether.transfer.ArtifactNotFoundException
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferCancelledException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

private const val SHA256_HEX_LENGTH = 64
private const val KIBIBYTE = 1_024
private const val MEBIBYTE = 1_048_576L
private const val DEFAULT_VERIFIED_CACHE_MEBIBYTES = 512L
private const val CACHE_DOWNLOAD_RESERVATION_PATHS = 512
private const val CACHE_OFFLINE_RESERVATION_PATHS = 32
private const val CACHE_OFFLINE_RESERVATION_BYTES = 64L * KIBIBYTE
private const val MAX_SOURCE_DOWNLOAD_BYTES = VERIFIED_SOURCE_MAX_ARTIFACT_BYTES
private const val MAX_CHECKSUM_BYTES = KIBIBYTE
private val SHA256 = Regex("[a-f0-9]{$SHA256_HEX_LENGTH}")

private data class ResolutionContext(
    val coordinates: ResolvedArtifactCoordinates,
    val repositories: List<RemoteRepository>,
    val session: org.eclipse.aether.RepositorySystemSession,
    val budget: SourceTransferBudget,
    val localRepository: Path,
)

private sealed interface ArtifactVersionFetch {
    data class Resolved(val version: String) : ArtifactVersionFetch
    data object Missing : ArtifactVersionFetch
    data object Failed : ArtifactVersionFetch
}

private fun Path.toVerifiedSourceResult(
    expectedSha256: String,
    checksumSidecar: Path,
    localRepository: Path,
): VerifiedSourceArtifactResult {
    if (!Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS)) return VerifiedSourceArtifactResult.Failed

    return when (val digest = sha256Bounded()) {
        null -> VerifiedSourceArtifactResult.LimitExceeded
        expectedSha256 -> VerifiedSourceArtifactResult.Available(this, expectedSha256)
        else -> {
            removeCachedArtifactSafely(localRepository)
            checksumSidecar.removeCachedArtifactSafely(localRepository)
            VerifiedSourceArtifactResult.Failed
        }
    }
}

/** The caller holds the managed cache root lock while removing one rejected Aether artifact. */
private fun Path.removeCachedArtifactSafely(localRepository: Path) {
    val repositoryRoot = localRepository.toAbsolutePath().normalize()
    val artifact = toAbsolutePath().normalize()
    if (
        artifact == repositoryRoot ||
        !artifact.startsWith(repositoryRoot) ||
        Files.isSymbolicLink(repositoryRoot)
    ) {
        return
    }

    val realRepositoryRoot = repositoryRoot.toRealPath()
    val realParent = parent?.toRealPath() ?: return
    val candidate = realParent.resolve(fileName)
    if (
        !realParent.startsWith(realRepositoryRoot) ||
        !candidate.startsWith(realRepositoryRoot) ||
        !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
    ) {
        return
    }

    Files.deleteIfExists(candidate)
}

private fun Path.sha256Bounded(): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    Files.newInputStream(this, LinkOption.NOFOLLOW_LINKS).use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > MAX_SOURCE_DOWNLOAD_BYTES - total) return null
            total += count
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun readChecksum(path: Path): String? = Files.newInputStream(path).use { input ->
    val bytes = input.readNBytes(MAX_CHECKSUM_BYTES + 1)
    if (bytes.size > MAX_CHECKSUM_BYTES) {
        null
    } else {
        bytes.toString(Charsets.US_ASCII).trim().substringBefore(' ').lowercase().takeIf(SHA256::matches)
    }
}

private fun repositoryLocation(value: String): String {
    val uri = java.net.URI(value)
    return java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
}

private fun resolveSnapshotArtifactVersion(context: ResolutionContext, classifier: String): ArtifactVersionFetch {
    val baseVersion = context.coordinates.version
    if (!baseVersion.endsWith("-SNAPSHOT")) return ArtifactVersionFetch.Resolved(baseVersion)

    val coordinate = context.coordinates
    val request = VersionRequest().apply {
        artifact = DefaultArtifact(coordinate.group, coordinate.module, classifier, "jar", baseVersion)
        repositories = context.repositories
    }
    return try {
        val result = AetherSessionFactory.repositorySystem.resolveVersion(context.session, request)
        context.budget.ensureWithinBudget()
        artifactVersionResult(result, coordinate, classifier)
    } catch (failure: VersionResolutionException) {
        context.budget.ensureWithinBudget()
        versionResolutionFailure(failure)
    } catch (_: TransferCancelledException) {
        context.budget.ensureWithinBudget()
        ArtifactVersionFetch.Failed
    }
}

private fun artifactVersionResult(
    result: VersionResult,
    coordinates: ResolvedArtifactCoordinates,
    classifier: String,
): ArtifactVersionFetch {
    if (result.exceptions.any { it !is MetadataNotFoundException }) return ArtifactVersionFetch.Failed

    val resolvedVersion = result.version
    if (resolvedVersion == null || resolvedVersion == coordinates.version) return ArtifactVersionFetch.Missing

    val resolvedArtifact = DefaultArtifact(
        coordinates.group,
        coordinates.module,
        classifier,
        "jar",
        resolvedVersion,
    )
    return if (resolvedArtifact.isSnapshot && resolvedArtifact.baseVersion == coordinates.version) {
        ArtifactVersionFetch.Resolved(resolvedVersion)
    } else {
        ArtifactVersionFetch.Failed
    }
}

private fun versionResolutionFailure(failure: VersionResolutionException): ArtifactVersionFetch = if (
    failure.result.exceptions.isNotEmpty() &&
    failure.result.exceptions.all { it is MetadataNotFoundException }
) {
    ArtifactVersionFetch.Missing
} else {
    ArtifactVersionFetch.Failed
}

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
    ): VerifiedSourceArtifactResult {
        val cancellation = SourceTransferCancellation()
        return withTimeoutOrNull(SOURCE_REQUEST_TIMEOUT_MS) {
            coroutineScope {
                val resolution = async(Dispatchers.IO) {
                    runInterruptible { resolveVerifiedRequest(request, cancellation) }
                }
                try {
                    resolution.await()
                } catch (cancelled: CancellationException) {
                    // Abort HTTP sockets before this scope waits for its interrupted worker to finish.
                    // Thread interruption alone does not stop Apache HTTP response-body cleanup drains.
                    cancellation.cancel()
                    throw cancelled
                }
            }
        } ?: VerifiedSourceArtifactResult.Failed
    }

    private fun resolveVerifiedRequest(
        request: VerifiedSourceArtifactRequest,
        cancellation: SourceTransferCancellation,
    ): VerifiedSourceArtifactResult {
        if (
            !supportsVerifiedSourceCoordinates(request.coordinates) ||
            !SHA256.matches(request.selectedBinarySha256)
        ) {
            return VerifiedSourceArtifactResult.UnsupportedArtifact
        }
        return try {
            resolveVerified(request, defaultVerifiedSourceCacheRoot(cacheDir), cancellation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SourceResolverCacheLimit) {
            VerifiedSourceArtifactResult.LimitExceeded
        } catch (_: java.io.IOException) {
            VerifiedSourceArtifactResult.Failed
        }
    }

    private fun resolveVerified(
        request: VerifiedSourceArtifactRequest,
        root: Path,
        cancellation: SourceTransferCancellation,
    ): VerifiedSourceArtifactResult {
        var binaryMismatchObserved = false
        for (repository in repositoryProvider.getRepositories()) {
            val key = repositoryCacheKey(request, repository)
            if (!request.allowDownload && !Files.isDirectory(root.resolve(key))) continue
            // Release artifacts already verified in this repository can be reused without admitting
            // another download. Snapshots must still consult the repository for newer metadata.
            if (shouldTryCachedRelease(request, root.resolve(key))) {
                val cached = withSourceResolverCapacity(
                    root,
                    verifiedCacheBytes,
                    CACHE_OFFLINE_RESERVATION_BYTES,
                    CACHE_OFFLINE_RESERVATION_PATHS,
                ) { canonical ->
                    resolveRepository(
                        request.copy(allowDownload = false),
                        repository,
                        canonical.resolve(key),
                        cancellation,
                    )
                }
                if (cached is VerifiedSourceArtifactResult.Available) return cached
            }
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
                resolveRepository(request, repository, canonical.resolve(key), cancellation)
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

    private fun shouldTryCachedRelease(request: VerifiedSourceArtifactRequest, local: Path): Boolean =
        request.allowDownload &&
            !request.coordinates.version.endsWith("-SNAPSHOT") &&
            Files.isDirectory(local)

    private fun resolveRepository(
        request: VerifiedSourceArtifactRequest,
        repository: RemoteRepository,
        local: Path,
        cancellation: SourceTransferCancellation,
    ): VerifiedSourceArtifactResult? {
        val coordinate = request.coordinates
        val budget = SourceTransferBudget(
            maxOperationBytes = VERIFIED_SOURCE_MAX_OPERATION_BYTES,
            maxSourceBytes = VERIFIED_SOURCE_MAX_ARTIFACT_BYTES,
            maxMetadataBytes = VERIFIED_SOURCE_MAX_METADATA_BYTES,
            maxChecksumBytes = VERIFIED_SOURCE_MAX_CHECKSUM_BYTES,
            cancelTransfers = cancellation::cancel,
        )
        val session = verifiedSession(local, request.allowDownload, budget, cancellation)
        val context = ResolutionContext(coordinate, listOf(repository), session, budget, local)
        return when (val binary = verifySelectedBinary(context, request.selectedBinarySha256)) {
            BinaryCheck.TryNextRepository -> null
            is BinaryCheck.SelectedBinaryMatches -> resolveSourceArtifact(context, binary.version)
            is BinaryCheck.Decided -> binary.result
        }
    }

    private fun verifySelectedBinary(context: ResolutionContext, selectedBinarySha256: String): BinaryCheck =
        when (val artifactVersion = resolveSnapshotArtifactVersion(context, "")) {
            is ArtifactVersionFetch.Resolved -> when (
                val checksum = resolveChecksum(context, "", "jar.sha256", artifactVersion.version)
            ) {
                is ChecksumFetch.Valid -> if (checksum.sha256 == selectedBinarySha256) {
                    BinaryCheck.SelectedBinaryMatches(artifactVersion.version)
                } else {
                    BinaryCheck.Decided(VerifiedSourceArtifactResult.BinaryMismatch)
                }
                ChecksumFetch.Missing -> BinaryCheck.TryNextRepository
                ChecksumFetch.Invalid -> BinaryCheck.Decided(VerifiedSourceArtifactResult.IntegrityUnavailable)
                ChecksumFetch.Failed -> BinaryCheck.Decided(VerifiedSourceArtifactResult.Failed)
            }
            ArtifactVersionFetch.Missing -> BinaryCheck.TryNextRepository
            ArtifactVersionFetch.Failed -> BinaryCheck.Decided(VerifiedSourceArtifactResult.Failed)
        }

    private fun resolveSourceArtifact(
        context: ResolutionContext,
        selectedBinaryVersion: String,
    ): VerifiedSourceArtifactResult = when (val artifactVersion = resolveSnapshotArtifactVersion(context, "sources")) {
        is ArtifactVersionFetch.Resolved -> if (artifactVersion.version == selectedBinaryVersion) {
            resolveSourceVersion(context, artifactVersion.version)
        } else {
            VerifiedSourceArtifactResult.IntegrityUnavailable
        }
        ArtifactVersionFetch.Missing -> VerifiedSourceArtifactResult.IntegrityUnavailable
        ArtifactVersionFetch.Failed -> VerifiedSourceArtifactResult.Failed
    }

    private fun resolveSourceVersion(
        context: ResolutionContext,
        artifactVersion: String,
    ): VerifiedSourceArtifactResult = when (
        val checksum = resolveChecksum(context, "sources", "jar.sha256", artifactVersion)
    ) {
        is ChecksumFetch.Valid -> when (
            val source = resolveArtifact(context, "sources", "jar", artifactVersion)
        ) {
            is ArtifactFetch.Found -> source.path.toVerifiedSourceResult(
                checksum.sha256,
                checksum.path,
                context.localRepository,
            )
            ArtifactFetch.Missing -> VerifiedSourceArtifactResult.NotAvailable
            ArtifactFetch.Failed -> VerifiedSourceArtifactResult.Failed
        }
        ChecksumFetch.Missing, ChecksumFetch.Invalid -> VerifiedSourceArtifactResult.IntegrityUnavailable
        ChecksumFetch.Failed -> VerifiedSourceArtifactResult.Failed
    }

    private fun resolveChecksum(
        context: ResolutionContext,
        classifier: String,
        extension: String,
        artifactVersion: String,
    ): ChecksumFetch = when (
        val fetched = resolveArtifact(context, classifier, extension, artifactVersion)
    ) {
        is ArtifactFetch.Found -> readChecksum(fetched.path)?.let { sha256 ->
            ChecksumFetch.Valid(sha256, fetched.path)
        } ?: ChecksumFetch.Invalid
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

    private fun verifiedSession(
        local: Path,
        allowDownload: Boolean,
        budget: SourceTransferBudget,
        cancellation: SourceTransferCancellation,
    ): DefaultRepositorySystemSession =
        DefaultRepositorySystemSession(AetherSessionFactory.createSession(local)).apply {
            isOffline = !allowDownload
            data.set(SourceTransferCancellation.SESSION_KEY, cancellation)
            // A private connection manager lets transporter.close abort this operation's sockets only.
            setConfigProperty("aether.connector.http.cacheState", false)
            // SHA sidecars are fetched explicitly and verified below; ignore disables Aether's unobserved fetch path.
            checksumPolicy = "ignore"
            updatePolicy = "always" // Explicit Retry can recover from earlier missing/auth/transport results.
            setConfigProperty("aether.checksums.algorithms", "SHA-256")
            setConfigProperty("aether.checksums.omitChecksumsForExtensions", ".asc,.sigstore,.sigstore.json,.sha256")
            setConfigProperty("aether.artifactResolver.snapshotNormalization", false)
            setConfigProperty("aether.connector.basic.threads", 1)
            setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, SOURCE_CONNECT_TIMEOUT_MS)
            setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, SOURCE_READ_TIMEOUT_MS)
            setConfigProperty("aether.connector.http.retryHandler.count", 0)
            transferListener = budget
        }

    private fun resolveArtifact(
        context: ResolutionContext,
        classifier: String,
        extension: String,
        artifactVersion: String,
    ): ArtifactFetch {
        val request = ArtifactRequest().apply {
            artifact = DefaultArtifact(
                context.coordinates.group,
                context.coordinates.module,
                classifier,
                extension,
                artifactVersion,
            )
            repositories = context.repositories
        }
        return try {
            val result = AetherSessionFactory.repositorySystem.resolveArtifact(context.session, request)
            context.budget.ensureWithinBudget()
            result.artifact?.file?.toPath()?.let(ArtifactFetch::Found) ?: ArtifactFetch.Failed
        } catch (failure: ArtifactResolutionException) {
            context.budget.ensureWithinBudget()
            val errors = failure.results.flatMap { it.exceptions }
            if (errors.isNotEmpty() && errors.all { it is ArtifactNotFoundException }) {
                ArtifactFetch.Missing
            } else {
                ArtifactFetch.Failed
            }
        } catch (_: TransferCancelledException) {
            context.budget.ensureWithinBudget()
            ArtifactFetch.Failed
        }
    }

    private sealed interface ArtifactFetch {
        data class Found(val path: Path) : ArtifactFetch
        data object Missing : ArtifactFetch
        data object Failed : ArtifactFetch
    }

    private sealed interface ChecksumFetch {
        data class Valid(val sha256: String, val path: Path) : ChecksumFetch
        data object Missing : ChecksumFetch
        data object Invalid : ChecksumFetch
        data object Failed : ChecksumFetch
    }

    private sealed interface BinaryCheck {
        data object TryNextRepository : BinaryCheck
        data class SelectedBinaryMatches(val version: String) : BinaryCheck
        data class Decided(val result: VerifiedSourceArtifactResult) : BinaryCheck
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
        private const val SOURCE_CACHE_RESERVATION_BYTES = VERIFIED_SOURCE_CACHE_RESERVATION_BYTES

        /**
         * Default cache directory: ~/.groovy-lsp/cache/sources
         */
        fun getDefaultCacheDir(): Path = defaultSourceArtifactCacheRoot()
    }
}
