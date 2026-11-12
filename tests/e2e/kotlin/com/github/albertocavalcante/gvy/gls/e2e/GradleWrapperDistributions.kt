// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.UncheckedIOException
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
 * not used, which would otherwise reach into the host's other distributions. This relies on every
 * fixture pinning the repository's single Gradle version (enforced by GradleWrapperDistributionsTest):
 * a daemon of a newer fixture version could clean an older linked host distribution through its
 * link. Daemons, caches and init scripts stay isolated.
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

    /**
     * Creates the isolated Gradle user [home], then runs [share] on it.
     *
     * Returns null only when the home itself cannot be created. A failing [share] is logged and the
     * isolated home is still returned: falling back to the host home would silently undo isolation.
     */
    @Suppress("TooGenericExceptionCaught") // Sharing is an optimisation; any failure must leave isolation intact.
    fun prepareIsolatedHome(home: Path, share: (Path) -> Unit): Path? {
        try {
            Files.createDirectories(home)
        } catch (e: IOException) {
            logger.warn { "Failed to prepare isolated Gradle user home at $home: ${e.message}" }
            return null
        }
        try {
            share(home)
        } catch (e: Exception) {
            logger.warn { "Not sharing Gradle distributions with $home; Gradle will download them: ${e.message}" }
        }
        return home
    }

    /** Distribution folder names (e.g. `gradle-9.8.2-bin`) that the fixture wrappers under [workspaces] install. */
    fun fixtureDistributions(workspaces: Path): Set<String> {
        if (!Files.isDirectory(workspaces)) return emptySet()
        return try {
            Files.walk(workspaces).use { paths ->
                paths.filter { it.isRegularFile() && it.endsWith(WRAPPER_PROPERTIES) }
                    .toList()
                    .mapNotNull { distributionName(it) }
                    .toSet()
            }
        } catch (e: IOException) {
            logger.warn { "Cannot read fixture Gradle wrappers under $workspaces: ${e.message}" }
            emptySet()
        } catch (e: UncheckedIOException) {
            logger.warn { "Cannot read fixture Gradle wrappers under $workspaces: ${e.message}" }
            emptySet()
        }
    }

    /**
     * Links each of [distributions] that is installed in [hostHome] into [isolatedHome].
     *
     * Returns the names the isolated home now shares. This code never creates anything in the host home
     * (Gradle still writes its lock files there). A distribution that cannot be linked is skipped and
     * downloaded by Gradle as before; [prepareIsolatedHome] contains any other failure.
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
