package com.github.albertocavalcante.groovylsp.buildtool

import org.slf4j.LoggerFactory

/**
 * Data class containing structured information about an ASM error.
 *
 * @property majorVersion The class file major version that caused the error (e.g., 69)
 * @property jdkVersion The JDK version corresponding to the major version (e.g., 25), or null if unknown
 * @property currentJdk The JDK version currently running the LSP
 * @property suggestions List of actionable suggestions to resolve the error
 */
data class AsmErrorInfo(
    val majorVersion: Int,
    val jdkVersion: Int?,
    val currentJdk: Int,
    val suggestions: List<String>,
)

/**
 * Analyzes exceptions to detect and extract information from ASM "Unsupported class file major version" errors.
 * These errors indicate that code was compiled with a newer JDK than Groovy can handle.
 */
class AsmErrorAnalyzer {

    private val logger = LoggerFactory.getLogger(AsmErrorAnalyzer::class.java)

    // Lazy initialization to avoid circular dependency issues
    private val compatibilityService: GroovyCompatibilityService by lazy {
        GroovyCompatibilityService()
    }

    /**
     * Checks if the exception chain contains an ASM "Unsupported class file major version" error.
     *
     * @param t The throwable to check
     * @return true if the exception chain contains an ASM error
     */
    fun isAsmError(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("Unsupported class file major version", ignoreCase = true)
    }

    /**
     * Analyzes an exception and extracts structured information if it's an ASM error.
     *
     * @param t The throwable to analyze
     * @return AsmErrorInfo containing details about the error, or null if not an ASM error
     */
    fun analyze(t: Throwable): AsmErrorInfo? {
        if (!isAsmError(t)) return null

        // Find the exception in the chain with the ASM error message
        var current: Throwable? = t
        while (current != null) {
            val message = current.message
            if (message != null && message.contains("Unsupported class file major version", ignoreCase = true)) {
                // Parse the major version from the error message
                val majorVersion = JdkVersionMapper.parseMajorVersionFromError(message) ?: return null
                val jdkVersion = JdkVersionMapper.toJdkVersion(majorVersion)
                val currentJdk = getCurrentJdkVersion()

                val suggestions = buildSuggestions(jdkVersion, currentJdk)

                return AsmErrorInfo(
                    majorVersion = majorVersion,
                    jdkVersion = jdkVersion,
                    currentJdk = currentJdk,
                    suggestions = suggestions,
                )
            }
            current = current.cause
        }

        return null
    }

    /**
     * Gets the current JDK version running the LSP.
     */
    private fun getCurrentJdkVersion(): Int {
        val version = System.getProperty("java.version")
        return parseJdkVersionFromString(version) ?: 17 // Default to 17 if we can't parse
    }

    /**
     * Parses a JDK version string like "17.0.1", "1.8.0_292", "21", etc.
     */
    private fun parseJdkVersionFromString(version: String): Int? {
        return runCatching {
            // Handle both "1.8.0" format (old) and "17.0.1" format (new)
            val parts = version.split(".")
            if (parts.isEmpty()) return null

            val firstPart = parts[0]
            if (firstPart == "1" && parts.size > 1) {
                // Old format: 1.8.0 -> 8
                parts[1].toIntOrNull()
            } else {
                // New format: 17.0.1 -> 17
                firstPart.toIntOrNull()
            }
        }.getOrNull()
    }

    /**
     * Builds actionable suggestions based on the detected JDK version and current environment.
     */
    private fun buildSuggestions(targetJdkVersion: Int?, currentJdk: Int): List<String> {
        val suggestions = mutableListOf<String>()

        if (targetJdkVersion != null) {
            // Suggest upgrading Groovy to a version that supports the target JDK
            val recommendedGroovy = compatibilityService.suggestGroovyVersion(targetJdkVersion)
            if (recommendedGroovy != null) {
                suggestions.add("Upgrade to $recommendedGroovy which supports JDK $targetJdkVersion")
            }

            // Suggest downgrading the project's compiled classes
            if (targetJdkVersion > currentJdk) {
                suggestions.add("Recompile dependencies with JDK $currentJdk or earlier")
            }

            // Suggest running with a newer JDK
            suggestions.add("Run the LSP with JDK $targetJdkVersion or later")
        } else {
            // Generic suggestions when we don't know the target JDK
            suggestions.add("Upgrade to the latest Groovy version for broader JDK support")
            suggestions.add("Check if dependencies were compiled with a newer JDK than Groovy supports")
        }

        return suggestions
    }

    /**
     * Searches the exception chain for a message matching the given predicate.
     */
    private fun searchExceptionChain(t: Throwable, predicate: (String) -> Boolean): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val message = current.message
            if (message != null && predicate(message)) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
