package com.github.albertocavalcante.groovylsp.buildtool.jdk

/**
 * Represents JDK version requirements extracted from project build configuration.
 *
 * @property sourceVersion JDK version for source compatibility (e.g., 17)
 * @property targetVersion JDK version for target/release compatibility
 * @property toolchainVersion JDK version from toolchain configuration (takes precedence)
 * @property source Where the requirement was found
 */
data class JdkRequirement(
    val sourceVersion: Int?,
    val targetVersion: Int?,
    val toolchainVersion: Int?,
    val source: RequirementSource,
) {
    /**
     * The effective JDK version required to build/run this project.
     * Priority: toolchain > target > source
     */
    val effectiveVersion: Int?
        get() = toolchainVersion ?: targetVersion ?: sourceVersion
}

/**
 * Indicates where a JDK requirement was discovered.
 */
enum class RequirementSource(val displayName: String) {
    MAVEN_COMPILER_PLUGIN("maven-compiler-plugin"),
    MAVEN_TOOLCHAIN("Maven toolchains.xml"),
    MAVEN_RELEASE_PROPERTY("maven.compiler.release"),
    MAVEN_SOURCE_TARGET_PROPERTY("maven.compiler.source/target"),
    GRADLE_TOOLCHAIN("Java toolchain"),
    GRADLE_SOURCE_COMPATIBILITY("sourceCompatibility"),
    GRADLE_TARGET_COMPATIBILITY("targetCompatibility"),
    PROJECT_FILE_JAVA_VERSION(".java-version"),
    PROJECT_FILE_SDKMANRC(".sdkmanrc"),
    UNKNOWN("build configuration"),
}

/**
 * Result of JDK requirement extraction.
 */
sealed interface JdkRequirementResult {
    /** A JDK requirement was found. */
    data class Found(val requirement: JdkRequirement) : JdkRequirementResult

    /** No JDK requirement is configured in the project. */
    data class NotConfigured(val buildTool: String) : JdkRequirementResult

    /** Failed to parse the build configuration. */
    data class ParseError(val message: String, val cause: Throwable? = null) : JdkRequirementResult
}

/**
 * Interface for extracting JDK requirements from project configurations.
 */
interface JdkRequirementExtractor {
    /**
     * Extracts JDK version requirements from the project.
     *
     * @param workspaceRoot The root directory of the project
     * @return The extraction result
     */
    fun extract(workspaceRoot: java.nio.file.Path): JdkRequirementResult
}
