package com.github.albertocavalcante.gvy.gls.e2e

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

/**
 * Lets the isolated E2E Gradle user home reuse the host's installed fixture distributions.
 *
 * Each test JVM gets a fresh Gradle user home, so without sharing every CI shard downloaded the
 * fixtures' Gradle distribution from services.gradle.org. When that host answered 504 or timed out
 * (2026-11-09), the first Gradle scenario of a shard failed dependency resolution and timed out
 * waiting for a success notification.
 *
 * Only the fixtures' own `wrapper/dists/<name>` folders are linked, never the whole `wrapper/dists`:
 * a daemon's distribution cleanup deletes versions older than its own that its (isolated) home has
 * not used, which would otherwise reach into the host's other distributions. Fixtures pin the
 * daemon's own version, which that cleanup never removes. Daemons, caches and init scripts stay
 * isolated.
 */
internal object GradleWrapperDistributions {
    private val logger = KotlinLogging.logger {}

    /** Set by the Gradle build to the outer build's resolved Gradle user home. */
    const val HOST_HOME_PROPERTY = "groovy.lsp.e2e.hostGradleUserHome"

    private const val DISTS = "wrapper/dists"
    private const val WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"

    /** The Gradle user home of the build running the tests (falls back to Gradle's own defaults). */
    fun hostGradleUserHome(): Path =
        sequenceOf(System.getProperty(HOST_HOME_PROPERTY), System.getenv("GRADLE_USER_HOME"))
            .firstOrNull { !it.isNullOrBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".gradle")

    /** Distribution folder names (e.g. `gradle-9.8.2-bin`) that the fixture wrappers under [workspaces] install. */
    fun fixtureDistributions(workspaces: Path): Set<String> {
        if (!Files.isDirectory(workspaces)) return emptySet()
        return Files.walk(workspaces).use { paths ->
            paths.filter { it.isRegularFile() && it.endsWith(WRAPPER_PROPERTIES) }
                .toList()
                .mapNotNull { distributionName(it) }
                .toSet()
        }
    }

    /**
     * Links each of [distributions] that is installed in [hostHome] into [isolatedHome].
     *
     * Returns the names the isolated home now shares. This code never creates anything in the host home
     * (Gradle still writes its lock files there) and never fails the caller: a distribution that cannot be
     * linked is downloaded by Gradle as before.
     */
    fun share(hostHome: Path, isolatedHome: Path, distributions: Set<String>): List<String> =
        distributions.filter { name ->
            link(hostHome.resolve(DISTS).resolve(name), isolatedHome.resolve(DISTS).resolve(name))
        }

    private fun link(hostDist: Path, isolatedDist: Path): Boolean = try {
        if (Files.isSymbolicLink(isolatedDist) && !Files.exists(isolatedDist)) {
            Files.delete(isolatedDist) // A dangling link would make Gradle fail instead of downloading.
        }
        when {
            !Files.isDirectory(hostDist) -> false
            Files.exists(isolatedDist, LinkOption.NOFOLLOW_LINKS) -> Files.isSameFile(isolatedDist, hostDist)
            else -> {
                Files.createDirectories(isolatedDist.parent)
                Files.createSymbolicLink(isolatedDist, hostDist.toAbsolutePath())
                logger.info { "Sharing Gradle distribution $hostDist" }
                true
            }
        }
    } catch (e: IOException) {
        logger.warn { "Cannot share Gradle distribution $hostDist: ${e.message}" }
        false
    } catch (e: UnsupportedOperationException) {
        logger.warn { "Cannot share Gradle distribution $hostDist: ${e.message}" }
        false
    }

    private fun distributionName(wrapperProperties: Path): String? {
        val url = Properties().apply { wrapperProperties.inputStream().use(::load) }.getProperty("distributionUrl")
        return url?.substringAfterLast('/')?.removeSuffix(".zip")?.takeIf { it.isNotBlank() }
    }
}
