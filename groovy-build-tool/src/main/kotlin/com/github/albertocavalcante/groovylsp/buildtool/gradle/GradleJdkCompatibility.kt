package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.gradle.util.GradleVersion

/**
 * Utility for checking Gradle and JDK version compatibility.
 *
 * Based on the official Gradle compatibility matrix:
 * https://docs.gradle.org/current/userguide/compatibility.html
 *
 * Key thresholds:
 * - Java 17 requires Gradle 7.2+
 * - Java 18 requires Gradle 7.5+
 * - Java 19 requires Gradle 7.6+
 * - Java 20 requires Gradle 8.3+
 * - Java 21 requires Gradle 8.5+
 * - Java 22 requires Gradle 8.8+
 * - Java 23 requires Gradle 8.10+
 * - Java 24 requires Gradle 8.14+
 */
object GradleJdkCompatibility {

    /**
     * Maps JDK major version to minimum required Gradle version.
     * Only includes JDK 17+ where strict compatibility requirements exist.
     */
    @Suppress("MagicNumber") // JDK and Gradle versions are inherently meaningful numbers
    private val MINIMUM_GRADLE_FOR_JDK: Map<Int, GradleVersion> = mapOf(
        17 to GradleVersion.version("7.2"),
        18 to GradleVersion.version("7.5"),
        19 to GradleVersion.version("7.6"),
        20 to GradleVersion.version("8.3"),
        21 to GradleVersion.version("8.5"),
        22 to GradleVersion.version("8.8"),
        23 to GradleVersion.version("8.10"),
        24 to GradleVersion.version("8.14"),
    )

    /**
     * Checks if a Gradle version is compatible with a JDK major version.
     *
     * @param gradleVersion The Gradle version string (e.g., "8.0.2")
     * @param jdkMajorVersion The JDK major version (e.g., 21)
     * @return true if compatible, false otherwise
     */
    fun isSupported(gradleVersion: String, jdkMajorVersion: Int): Boolean {
        val minGradle = MINIMUM_GRADLE_FOR_JDK[jdkMajorVersion] ?: return true
        return runCatching {
            GradleVersion.version(gradleVersion).baseVersion >= minGradle.baseVersion
        }.getOrElse { false }
    }

    /**
     * Gets the minimum Gradle version required for a JDK major version.
     *
     * @param jdkMajorVersion The JDK major version
     * @return The minimum Gradle version string, or null if no minimum exists
     */
    fun getMinimumGradleVersion(jdkMajorVersion: Int): String? = MINIMUM_GRADLE_FOR_JDK[jdkMajorVersion]?.version

    /**
     * Gets the current JDK major version.
     */
    fun getCurrentJdkMajorVersion(): Int = Runtime.version().feature()

    /**
     * Generates a user-friendly suggestion for fixing a JDK/Gradle incompatibility.
     *
     * @param gradleVersion The project's Gradle version
     * @param jdkMajorVersion The JDK version running the LSP
     * @return A descriptive message with suggestions
     */
    fun suggestFix(gradleVersion: String, jdkMajorVersion: Int): String {
        val minGradle = getMinimumGradleVersion(jdkMajorVersion)
            ?: return "Gradle $gradleVersion may not be compatible with JDK $jdkMajorVersion."

        return buildString {
            append("Gradle $gradleVersion is not compatible with JDK $jdkMajorVersion. ")
            append("JDK $jdkMajorVersion requires Gradle $minGradle or higher.\n")
            append("Suggestions:\n")
            append("  1. Upgrade your project's Gradle wrapper: ./gradlew wrapper --gradle-version=$minGradle\n")
            append("  2. Run the LSP with a compatible JDK (17 or lower for Gradle $gradleVersion)\n")
            append("  3. Configure 'groovy.gradle.java.home' to point to a compatible JDK")
        }
    }
}
