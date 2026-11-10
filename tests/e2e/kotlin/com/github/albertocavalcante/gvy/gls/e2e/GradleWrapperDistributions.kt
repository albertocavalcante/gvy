package com.github.albertocavalcante.gvy.gls.e2e

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Lets the isolated E2E Gradle user home reuse the host's installed wrapper distributions.
 *
 * Each test JVM gets a fresh Gradle user home, so without sharing every CI shard downloaded the
 * fixtures' Gradle distribution from services.gradle.org. When that host answered 504 or timed out
 * (2026-11-09), the first Gradle scenario of a shard failed dependency resolution and timed out
 * waiting for a success notification. Only `wrapper/dists` is shared; daemons, caches and init
 * scripts stay isolated.
 */
internal object GradleWrapperDistributions {
    private val logger = KotlinLogging.logger {}

    private const val DISTS = "wrapper/dists"

    /** The Gradle user home of the process running the tests, as Gradle itself resolves it. */
    fun hostGradleUserHome(): Path = System.getenv("GRADLE_USER_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".gradle")

    /**
     * Links `<isolatedHome>/wrapper/dists` to `<hostHome>/wrapper/dists`.
     *
     * Returns true when the isolated home now uses the host distributions. Leaves an existing
     * isolated `wrapper/dists` untouched, and never fails the caller: without the link Gradle
     * simply downloads the distribution as before.
     */
    fun share(hostHome: Path, isolatedHome: Path): Boolean {
        val hostDists = hostHome.resolve(DISTS)
        val isolatedDists = isolatedHome.resolve(DISTS)
        return try {
            when {
                Files.exists(isolatedDists, LinkOption.NOFOLLOW_LINKS) -> {
                    Files.isSymbolicLink(isolatedDists) && Files.isSameFile(isolatedDists, hostDists)
                }
                isSameDirectory(hostHome, isolatedHome) -> false
                else -> {
                    Files.createDirectories(hostDists)
                    Files.createDirectories(isolatedDists.parent)
                    Files.createSymbolicLink(isolatedDists, hostDists.toAbsolutePath())
                    logger.info { "Sharing Gradle wrapper distributions from $hostDists" }
                    true
                }
            }
        } catch (e: IOException) {
            logger.warn { "Cannot share Gradle wrapper distributions from $hostDists: ${e.message}" }
            false
        } catch (e: UnsupportedOperationException) {
            logger.warn { "Cannot share Gradle wrapper distributions from $hostDists: ${e.message}" }
            false
        }
    }

    private fun isSameDirectory(a: Path, b: Path): Boolean =
        Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
}
