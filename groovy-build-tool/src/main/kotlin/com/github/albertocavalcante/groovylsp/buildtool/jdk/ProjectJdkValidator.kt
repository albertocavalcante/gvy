package com.github.albertocavalcante.groovylsp.buildtool.jdk

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Validates that the running JDK is compatible with project requirements.
 *
 * This performs EARLY DETECTION of JDK mismatches BEFORE any compilation,
 * following the fail-fast principle.
 */
class ProjectJdkValidator(
    private val mavenExtractor: MavenJdkRequirementExtractor = MavenJdkRequirementExtractor(),
    private val gradleExtractor: GradleJdkRequirementExtractor = GradleJdkRequirementExtractor(),
) {
    private val logger = LoggerFactory.getLogger(ProjectJdkValidator::class.java)

    companion object {
        /**
         * Difference threshold for warning about newer JDK.
         * If running JDK is more than this many versions newer than target,
         * we warn about potential bytecode compatibility issues.
         */
        private const val NEWER_JDK_WARNING_THRESHOLD = 2

        /**
         * Default JDK version to assume if detection fails.
         */
        private const val DEFAULT_JDK_VERSION = 17
    }

    /**
     * Extracts JDK requirements from the project and validates against the running JDK.
     *
     * @param workspaceRoot The root directory of the project
     * @param runningJdk The JDK version running the LSP (null = detect automatically)
     * @return ValidationResult indicating success, warning, or failure
     */
    fun validate(workspaceRoot: Path, runningJdk: Int? = null): ValidationResult {
        val currentJdk = runningJdk ?: getCurrentJdkVersion()
        logger.debug("Validating JDK compatibility. Running JDK: {}", currentJdk)

        // Extract requirement from project
        val requirement = extractRequirement(workspaceRoot)
        val effectiveVersion = requirement?.effectiveVersion
        if (requirement == null || effectiveVersion == null) {
            logger.debug("No JDK requirement configured, skipping validation")
            return ValidationResult.NoRequirement
        }

        val required = effectiveVersion
        logger.info(
            "Project JDK requirement: {} (from {}), Running JDK: {}",
            required,
            requirement.source.displayName,
            currentJdk,
        )

        return when {
            currentJdk < required -> {
                // Running JDK is OLDER than required - this will cause compilation failures
                logger.warn("Running JDK {} is older than project requirement {}", currentJdk, required)
                ValidationResult.IncompatibleOlder(
                    runningJdk = currentJdk,
                    requiredJdk = required,
                    source = requirement.source,
                    suggestions = buildOlderJdkSuggestions(currentJdk, required, requirement.source),
                )
            }

            currentJdk > required + NEWER_JDK_WARNING_THRESHOLD -> {
                // Running JDK is significantly NEWER - potential bytecode issues
                // This is the "major version 69" scenario
                logger.warn("Running JDK {} is significantly newer than project target {}", currentJdk, required)
                ValidationResult.PotentiallyIncompatibleNewer(
                    runningJdk = currentJdk,
                    targetJdk = required,
                    source = requirement.source,
                    suggestions = buildNewerJdkSuggestions(currentJdk, required, requirement.source),
                )
            }

            else -> {
                logger.debug("JDK validation passed: running JDK {}, required {}", currentJdk, required)
                ValidationResult.Compatible(runningJdk = currentJdk, requiredJdk = required)
            }
        }
    }

    /**
     * Extracts JDK requirement from the project, trying Maven first, then Gradle.
     */
    fun extractRequirement(workspaceRoot: Path): JdkRequirement? {
        // Try Maven first
        if (workspaceRoot.resolve("pom.xml").exists()) {
            val result = mavenExtractor.extract(workspaceRoot)
            if (result is JdkRequirementResult.Found) {
                return result.requirement
            }
        }

        // Try Gradle
        if (workspaceRoot.resolve("build.gradle").exists() ||
            workspaceRoot.resolve("build.gradle.kts").exists()
        ) {
            val result = gradleExtractor.extract(workspaceRoot)
            if (result is JdkRequirementResult.Found) {
                return result.requirement
            }
        }

        // Try project files (.java-version, .sdkmanrc)
        return extractFromProjectFiles(workspaceRoot)
    }

    private fun extractFromProjectFiles(workspaceRoot: Path): JdkRequirement? {
        // Check .java-version (jenv/asdf format)
        val javaVersionFile = workspaceRoot.resolve(".java-version")
        if (javaVersionFile.exists()) {
            runCatching {
                val content = java.nio.file.Files.readString(javaVersionFile).trim()
                parseJavaVersion(content)?.let { version ->
                    return JdkRequirement(
                        sourceVersion = null,
                        targetVersion = version,
                        toolchainVersion = null,
                        source = RequirementSource.PROJECT_FILE_JAVA_VERSION,
                    )
                }
            }.onFailure { e ->
                if (e is Error) throw e
                logger.debug("Failed to parse .java-version", e)
            }
        }

        // Check .sdkmanrc
        val sdkmanrcFile = workspaceRoot.resolve(".sdkmanrc")
        if (sdkmanrcFile.exists()) {
            runCatching {
                val content = java.nio.file.Files.readString(sdkmanrcFile)
                // Format: java=21.0.5-tem or java=17.0.1-zulu
                val javaLine = content.lines().find { it.startsWith("java=") }
                javaLine?.removePrefix("java=")?.let { versionStr ->
                    parseJavaVersion(versionStr)?.let { version ->
                        return JdkRequirement(
                            sourceVersion = null,
                            targetVersion = version,
                            toolchainVersion = null,
                            source = RequirementSource.PROJECT_FILE_SDKMANRC,
                        )
                    }
                }
            }.onFailure { e ->
                if (e is Error) throw e
                logger.debug("Failed to parse .sdkmanrc", e)
            }
        }

        return null
    }

    private fun getCurrentJdkVersion(): Int {
        val version = System.getProperty("java.version") ?: return DEFAULT_JDK_VERSION
        return parseJavaVersion(version) ?: DEFAULT_JDK_VERSION
    }

    private fun parseJavaVersion(version: String): Int? {
        val parts = version.split(".", "-", "_")
        return when {
            parts.isEmpty() -> null
            parts[0] == "1" && parts.size > 1 -> parts[1].toIntOrNull() // 1.8.0 -> 8
            else -> parts[0].toIntOrNull() // 17.0.1 -> 17
        }
    }

    private fun buildOlderJdkSuggestions(running: Int, required: Int, source: RequirementSource): List<String> = listOf(
        "The project requires JDK $required (from ${source.displayName}), but LSP is running JDK $running",
        "Configure groovy.java.home to use JDK $required or newer",
        "Install JDK $required: sdk install java $required-tem",
    )

    private fun buildNewerJdkSuggestions(running: Int, target: Int, source: RequirementSource): List<String> = listOf(
        "Project targets JDK $target (from ${source.displayName}), but LSP runs JDK $running",
        "Dependencies compiled with JDK $running may cause 'Unsupported class file major version' errors",
        "Configure groovy.java.home to use JDK $target to avoid bytecode compatibility issues",
        "Or update project to target JDK $running",
    )

    /**
     * Result of JDK validation.
     */
    sealed interface ValidationResult {
        /** No JDK requirement configured in project. */
        data object NoRequirement : ValidationResult

        /** Running JDK is compatible with project requirement. */
        data class Compatible(val runningJdk: Int, val requiredJdk: Int) : ValidationResult

        /** Running JDK is older than required - will cause compilation failures. */
        data class IncompatibleOlder(
            val runningJdk: Int,
            val requiredJdk: Int,
            val source: RequirementSource,
            val suggestions: List<String>,
        ) : ValidationResult

        /** Running JDK is significantly newer than target - may have bytecode issues. */
        data class PotentiallyIncompatibleNewer(
            val runningJdk: Int,
            val targetJdk: Int,
            val source: RequirementSource,
            val suggestions: List<String>,
        ) : ValidationResult
    }
}
