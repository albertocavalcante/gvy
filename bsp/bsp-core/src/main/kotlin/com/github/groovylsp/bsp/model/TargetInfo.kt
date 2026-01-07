package com.github.groovylsp.bsp.model

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.nio.file.Path

/**
 * Rich wrapper around [BuildTarget] with additional computed metadata.
 *
 * Provides convenient access to target properties and cached compilation information:
 * - Source directories and files
 * - Classpath entries
 * - Compiler options (javac, scalac)
 * - Derived properties (test/library flags)
 *
 * Based on Metals' target info pattern for efficient BSP data access.
 *
 * @property target The underlying BSP build target
 * @property sources List of source file paths for this target
 * @property classpath Classpath entries (JARs and directories)
 * @property javacOptions Java compiler options
 * @property scalacOptions Scala compiler options
 */
data class TargetInfo(
    val target: BuildTarget,
    val sources: List<Path> = emptyList(),
    val classpath: List<Path> = emptyList(),
    val javacOptions: List<String> = emptyList(),
    val scalacOptions: List<String> = emptyList(),
) {
    /**
     * The unique identifier for this build target.
     */
    val id: BuildTargetIdentifier
        get() = target.id

    /**
     * Human-readable display name, falling back to URI if not set.
     */
    val displayName: String
        get() = target.displayName ?: target.id.uri

    /**
     * Whether this target is a test target.
     */
    val isTest: Boolean
        get() = target.tags?.contains("test") == true

    /**
     * Whether this target is a library target.
     */
    val isLibrary: Boolean
        get() = target.tags?.contains("library") == true

    /**
     * Whether this target is an application target.
     */
    val isApplication: Boolean
        get() = target.tags?.contains("application") == true

    /**
     * All language IDs supported by this target.
     */
    val languageIds: Set<String>
        get() = target.languageIds?.toSet() ?: emptySet()

    /**
     * Dependencies as target identifiers.
     */
    val dependencies: List<BuildTargetIdentifier>
        get() = target.dependencies ?: emptyList()

    /**
     * The base directory of this target (if available).
     */
    val baseDirectory: String?
        get() = target.baseDirectory

    override fun toString(): String = "TargetInfo(id=${id.uri}, displayName=$displayName, " +
        "sources=${sources.size}, classpath=${classpath.size}, " +
        "isTest=$isTest, isLibrary=$isLibrary)"
}
