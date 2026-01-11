package com.github.albertocavalcante.groovylsp.buildtool.jdk

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Extracts JDK version requirements from Gradle projects by parsing build files.
 *
 * This uses regex-based parsing of build.gradle/build.gradle.kts files rather than
 * the Gradle Tooling API. This approach is:
 * - Safe: Doesn't trigger Gradle daemon or build script compilation
 * - Fast: No subprocess or JVM startup needed
 * - Reliable: Works even when Gradle/JDK are incompatible
 *
 * Checks (in priority order):
 * 1. Java toolchain configuration (languageVersion)
 * 2. sourceCompatibility setting
 * 3. targetCompatibility setting
 */
class GradleJdkRequirementExtractor : JdkRequirementExtractor {
    private val logger = KotlinLogging.logger {}

    companion object {
        // Toolchain patterns
        private val TOOLCHAIN_PATTERNS = listOf(
            // JavaLanguageVersion.of(17)
            Regex("""JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)"""),
            // languageVersion = 17 or languageVersion.set(17)
            Regex("""languageVersion\s*[=.]\s*(?:set\s*\(\s*)?(\d+)"""),
        )

        // Source compatibility patterns
        private val SOURCE_COMPATIBILITY_PATTERNS = listOf(
            // JavaVersion.VERSION_17 or VERSION_1_8
            Regex("""sourceCompatibility\s*=\s*JavaVersion\.VERSION_(\d+(?:_\d+)?)"""),
            // JavaVersion.toVersion(17) or JavaVersion.toVersion("17")
            Regex("""sourceCompatibility\s*=\s*JavaVersion\.toVersion\s*\(\s*["']?(\d+)["']?\s*\)"""),
            // Direct assignment: '17', "17", or 17
            Regex("""sourceCompatibility\s*=\s*['"]?(\d+(?:\.\d+)?)['"]?"""),
        )

        // Target compatibility patterns
        private val TARGET_COMPATIBILITY_PATTERNS = listOf(
            Regex("""targetCompatibility\s*=\s*JavaVersion\.VERSION_(\d+(?:_\d+)?)"""),
            Regex("""targetCompatibility\s*=\s*JavaVersion\.toVersion\s*\(\s*["']?(\d+)["']?\s*\)"""),
            Regex("""targetCompatibility\s*=\s*['"]?(\d+(?:\.\d+)?)['"]?"""),
        )

        // Java block source pattern
        private val JAVA_BLOCK_SOURCE_PATTERN = Regex(
            """java\s*\{[^}]*sourceCompatibility\s*=\s*(?:JavaVersion\.VERSION_)?['"]?(\d+(?:_\d+)?)['"]?""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    override fun extract(workspaceRoot: Path): JdkRequirementResult {
        val buildFile = findBuildFile(workspaceRoot)
        if (buildFile == null) {
            return JdkRequirementResult.NotConfigured("Gradle")
        }

        return runCatching {
            val content = Files.readString(buildFile)
            val isKts = buildFile.fileName.toString().endsWith(".kts")
            parseJdkRequirements(content, isKts)
        }.getOrElse { e ->
            if (e is Error) throw e
            logger.warn(e) { "Failed to parse Gradle build file for JDK requirements" }
            JdkRequirementResult.ParseError("Error parsing build file: ${e.message}", e)
        }
    }

    private fun findBuildFile(projectDir: Path): Path? {
        val candidates = listOf("build.gradle.kts", "build.gradle")
        return candidates.map { projectDir.resolve(it) }.firstOrNull { it.exists() }
    }

    private fun parseJdkRequirements(content: String, isKts: Boolean): JdkRequirementResult {
        var toolchainVersion: Int? = null
        var sourceCompatibility: Int? = null
        var targetCompatibility: Int? = null
        var source: RequirementSource = RequirementSource.UNKNOWN

        // Check for Java toolchain (highest priority)
        // Patterns:
        //   java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
        //   java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
        //   toolchain { languageVersion = JavaLanguageVersion.of(17) }
        for (pattern in TOOLCHAIN_PATTERNS) {
            pattern.find(content)?.let { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let { version ->
                    toolchainVersion = version
                    source = RequirementSource.GRADLE_TOOLCHAIN
                    logger.debug { "Found Gradle toolchain languageVersion: $version" }
                }
            }
            if (toolchainVersion != null) break
        }

        // Check for sourceCompatibility
        // Patterns:
        //   sourceCompatibility = JavaVersion.VERSION_17
        //   sourceCompatibility = '17'
        //   sourceCompatibility = "17"
        //   sourceCompatibility = 17
        //   sourceCompatibility = JavaVersion.toVersion(17)
        for (pattern in SOURCE_COMPATIBILITY_PATTERNS) {
            pattern.find(content)?.let { match ->
                parseVersionFromMatch(match.groupValues.getOrNull(1))?.let { version ->
                    sourceCompatibility = version
                    if (source == RequirementSource.UNKNOWN) {
                        source = RequirementSource.GRADLE_SOURCE_COMPATIBILITY
                    }
                    logger.debug { "Found Gradle sourceCompatibility: $version" }
                }
            }
            if (sourceCompatibility != null) break
        }

        // Check for targetCompatibility
        for (pattern in TARGET_COMPATIBILITY_PATTERNS) {
            pattern.find(content)?.let { match ->
                parseVersionFromMatch(match.groupValues.getOrNull(1))?.let { version ->
                    targetCompatibility = version
                    if (source == RequirementSource.UNKNOWN) {
                        source = RequirementSource.GRADLE_TARGET_COMPATIBILITY
                    }
                    logger.debug { "Found Gradle targetCompatibility: $version" }
                }
            }
            if (targetCompatibility != null) break
        }

        // Check for java extension block patterns
        // java { sourceCompatibility = JavaVersion.VERSION_17 }
        if (sourceCompatibility == null) {
            JAVA_BLOCK_SOURCE_PATTERN.find(content)?.let { match ->
                parseVersionFromMatch(match.groupValues.getOrNull(1))?.let { version ->
                    sourceCompatibility = version
                    source = RequirementSource.GRADLE_SOURCE_COMPATIBILITY
                    logger.debug { "Found Gradle java block sourceCompatibility: $version" }
                }
            }
        }

        if (toolchainVersion == null && sourceCompatibility == null && targetCompatibility == null) {
            return JdkRequirementResult.NotConfigured("Gradle")
        }

        return JdkRequirementResult.Found(
            JdkRequirement(
                sourceVersion = sourceCompatibility,
                targetVersion = targetCompatibility,
                toolchainVersion = toolchainVersion,
                source = source,
            ),
        )
    }

    /**
     * Parses version from match result.
     * Handles: "17", "1_8" (from VERSION_1_8), "17.0", "1.8"
     */
    private fun parseVersionFromMatch(value: String?): Int? {
        if (value.isNullOrBlank()) return null

        val trimmed = value.trim()
        return when {
            // VERSION_1_8 format -> 8
            trimmed.startsWith("1_") -> trimmed.removePrefix("1_").toIntOrNull()
            // 1.8 format -> 8
            trimmed.startsWith("1.") -> trimmed.removePrefix("1.").substringBefore(".").toIntOrNull()
            // 17.0 format -> 17
            trimmed.contains(".") -> trimmed.substringBefore(".").toIntOrNull()
            // Plain number -> direct parse
            else -> trimmed.toIntOrNull()
        }
    }
}
