package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.common.hash.sha256
import java.nio.file.Path

/**
 * Computes a fingerprint for workspace configuration to ensure cache coherency.
 *
 * When the workspace configuration changes (dependencies, source roots, etc.),
 * cached compilation results may become stale. This fingerprint allows the cache
 * to detect such changes and invalidate appropriately.
 *
 * Issue #743: Normalize parse modes and cache authority for cross-file resolution
 */
object ConfigurationFingerprint {

    /**
     * Computes a SHA-256 fingerprint of the workspace configuration.
     *
     * The fingerprint includes:
     * - Dependency classpath (order-sensitive, as classpath order matters)
     * - Source roots (order-sensitive for consistency)
     *
     * @param dependencies The dependency classpath
     * @param sourceRoots The source root directories
     * @return A 64-character hex string representing the configuration fingerprint
     */
    fun compute(dependencies: List<Path>, sourceRoots: List<Path>): String {
        val components = buildList {
            // Dependencies - order matters for classpath resolution
            add("deps:")
            dependencies.forEach { add(it.toAbsolutePath().toString()) }

            // Source roots
            add("roots:")
            sourceRoots.forEach { add(it.toAbsolutePath().toString()) }
        }

        return sha256(components.joinToString("\n"))
    }

    /**
     * Extends [base] with each artifact's observed stamp, in classpath order, so a jar replaced at the same path, or a
     * class rewritten in a class directory (#1364), yields a new key. A null stamp (not stat'ed or unreadable) is
     * keyed too.
     */
    fun withStamps(base: String, stamps: List<ArtifactStamp?>): String = sha256(
        buildString {
            append(base).append("\nstamps:")
            stamps.forEach { stamp ->
                append('\n').append(stamp?.let { "${it.size}:${it.lastModifiedMillis}:${it.files ?: ""}" } ?: "-")
            }
        },
    )
}
