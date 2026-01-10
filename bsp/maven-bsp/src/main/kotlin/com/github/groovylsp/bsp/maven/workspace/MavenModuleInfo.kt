package com.github.groovylsp.bsp.maven.workspace

import java.nio.file.Path

/**
 * Represents a Maven module parsed from a pom.xml file.
 *
 * This data class captures all relevant information about a Maven module
 * that is needed for BSP build target generation.
 */
data class MavenModuleInfo(
    /** Path to the pom.xml file for this module */
    val pomPath: Path,
    /** Maven group ID (e.g., "org.apache.maven") */
    val groupId: String,
    /** Maven artifact ID (e.g., "maven-core") */
    val artifactId: String,
    /** Maven version (e.g., "3.9.12") */
    val version: String,
    /** Packaging type (e.g., "jar", "pom", "war") */
    val packaging: String = "jar",
    /** List of child module directory names (for multi-module projects) */
    val modules: List<String> = emptyList(),
    /** Direct dependencies declared in this module */
    val dependencies: List<MavenDependency> = emptyList(),
    /** Parent module info if this module has a parent */
    val parent: ParentInfo? = null,
    /** Custom source directory if configured in build section */
    val sourceDirectory: String? = null,
    /** Custom test source directory if configured */
    val testSourceDirectory: String? = null,
) {
    /**
     * The base directory containing this module's pom.xml.
     */
    val baseDir: Path
        get() = pomPath.parent

    /**
     * Unique module identifier in format "groupId:artifactId".
     */
    val moduleId: String
        get() = "$groupId:$artifactId"

    /**
     * Full Maven coordinates in format "groupId:artifactId:version".
     */
    val coordinates: String
        get() = "$groupId:$artifactId:$version"

    /**
     * Whether this is a parent/aggregator POM (packaging=pom with modules).
     */
    val isAggregator: Boolean
        get() = packaging == "pom" && modules.isNotEmpty()
}

/**
 * Represents a Maven dependency.
 */
data class MavenDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String = "compile",
    val type: String = "jar",
    val classifier: String? = null,
    val optional: Boolean = false,
) {
    val coordinates: String
        get() = buildString {
            append("$groupId:$artifactId")
            version?.let { append(":$it") }
            if (classifier != null) append(":$classifier")
            if (type != "jar") append("@$type")
        }
}

/**
 * Represents parent POM reference.
 */
data class ParentInfo(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val relativePath: String? = null,
)
