// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.stubs

import com.github.albertocavalcante.gvy.build.maven.AetherSessionFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves where generated Jenkins stubs live.
 *
 * Stubs are server-owned derived data, so they are written under the groovy-lsp
 * cache (`~/.groovy-lsp/cache/jenkins-stubs`) instead of the user's workspace.
 * Each workspace gets its own subdirectory keyed by a hash of its normalized
 * absolute path, so concurrent workspaces never share or clobber stubs.
 */
object JenkinsStubsLocation {
    /**
     * JVM system property that replaces the default stubs cache root. Build test
     * tasks set it so test JVMs never write into the real user cache.
     */
    const val CACHE_ROOT_PROPERTY = "groovy.lsp.jenkins.stubsCacheRoot"

    internal const val CACHE_SUBDIR = "jenkins-stubs"
    private const val LEGACY_WORKSPACE_DIR = ".jenkins-stubs"
    private val logger = KotlinLogging.logger {}
    private val legacyWarnedRoots: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private const val HASH_HEX_LENGTH = 16
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Default root for all per-workspace stub directories. */
    fun defaultCacheRoot(): Path =
        resolveCacheRoot(System.getProperty(CACHE_ROOT_PROPERTY), AetherSessionFactory::getCacheDirectory)

    /**
     * Returns [override] when it is an absolute path, otherwise the [CACHE_SUBDIR] entry of the
     * groovy-lsp cache. Relative or invalid overrides are rejected with a warning, since they
     * would resolve against the server's working directory (often the user's workspace).
     * [groovyLspCacheDir] is only invoked when no override applies.
     */
    internal fun resolveCacheRoot(override: String?, groovyLspCacheDir: (String) -> Path): Path {
        if (override.isNullOrBlank()) return groovyLspCacheDir(CACHE_SUBDIR)
        val path = try {
            Paths.get(override)
        } catch (e: InvalidPathException) {
            logger.warn(e) { "Ignoring invalid $CACHE_ROOT_PROPERTY=$override" }
            return groovyLspCacheDir(CACHE_SUBDIR)
        }
        if (!path.isAbsolute) {
            logger.warn { "Ignoring relative $CACHE_ROOT_PROPERTY=$override; an absolute path is required" }
            return groovyLspCacheDir(CACHE_SUBDIR)
        }
        return path
    }

    /** True only the first time it is called for [workspaceRoot] (normalized) in this process. */
    internal fun claimLegacyWarning(workspaceRoot: Path): Boolean =
        legacyWarnedRoots.add(workspaceRoot.toAbsolutePath().normalize())

    /** The pre-cache stubs directory inside [workspaceRoot], if one is left over; never modified. */
    internal fun legacyWorkspaceDir(workspaceRoot: Path): Path? =
        workspaceRoot.resolve(LEGACY_WORKSPACE_DIR).takeIf { Files.isDirectory(it) }

    /**
     * Warning for a leftover [legacyDir]. Pure: it must not resolve the cache root, which can
     * fail and is only guarded inside stub generation.
     */
    internal fun legacyStubsWarning(legacyDir: Path): String =
        "Found $legacyDir from an older groovy-lsp version. It is no longer used and can be deleted; " +
            "generated stubs now live in the groovy-lsp cache (~/.groovy-lsp/cache/$CACHE_SUBDIR)."

    /** Returns the stub directory for [workspaceRoot] under [cacheRoot]. Pure; creates nothing. */
    fun directoryFor(cacheRoot: Path, workspaceRoot: Path): Path {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toString().toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_LENGTH)
        val name = normalized.fileName?.toString()?.replace(UNSAFE_NAME_CHARS, "_").orEmpty()
        val key = if (name.isEmpty()) hash else "$name-$hash"
        return cacheRoot.resolve(key)
    }
}
