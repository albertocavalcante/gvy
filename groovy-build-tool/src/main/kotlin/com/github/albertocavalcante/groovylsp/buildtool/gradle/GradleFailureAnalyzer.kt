package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionStatus

class GradleFailureAnalyzer {

    /**
     * Specifically detects the "Unsupported class file major version" error which indicates
     * a JDK/Gradle version mismatch.
     */
    fun isJdkMismatch(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("Unsupported class file major version", ignoreCase = true)
    }

    /**
     * Detects Java toolchain provisioning failures when Gradle cannot find or download
     * a matching JDK for the configured toolchain.
     *
     * Common causes:
     * - Required JDK version not installed locally
     * - Toolchain download repositories not configured (foojay plugin missing)
     * - org.gradle.java.installations.paths not set
     */
    fun isToolchainProvisioningError(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("Cannot find a Java installation", ignoreCase = true) ||
            message.contains("Toolchain download repositories have not been configured", ignoreCase = true) ||
            message.contains("ToolchainProvisioningException", ignoreCase = true)
    }

    /**
     * Detects errors related to init scripts (init.d, cp_init, etc).
     */
    fun isInitScriptError(t: Throwable): Boolean {
        // IMPORTANT: We MUST NOT classify JDK/toolchain errors as init script errors,
        // because we don't want to retry with isolated Gradle User Home for these issues.
        if (isJdkMismatch(t)) return false
        if (isToolchainProvisioningError(t)) return false

        return searchExceptionChain(t) { message ->
            message.contains("init.d", ignoreCase = true) ||
                message.contains("init script", ignoreCase = true) ||
                message.contains("cp_init", ignoreCase = true)
        }
    }

    /**
     * Detects transient errors that might benefit from a simple retry (e.g. file locks).
     */
    fun isTransient(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("waiting to lock", ignoreCase = true) ||
            message.contains("waiting for lock", ignoreCase = true) ||
            message.contains("Could not open build receipt cache", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true)
    }

    /**
     * Parsed information from a toolchain provisioning exception.
     */
    data class ToolchainErrorInfo(
        val requiredVersion: Int?,
        val vendor: String? = null,
        val platform: String? = null,
        val suggestions: List<String> = emptyList(),
    )

    /**
     * Extracts structured information from a toolchain provisioning exception.
     * Returns null if the exception is not a toolchain error.
     */
    fun extractToolchainErrorInfo(t: Throwable): ToolchainErrorInfo? {
        if (!isToolchainProvisioningError(t)) return null

        var current: Throwable? = t
        while (current != null) {
            val message = current.message ?: ""

            // Parse: "Cannot find a Java installation on your machine (Mac OS X 15.6 aarch64) matching: {languageVersion=17, vendor=any vendor}"
            val versionMatch = VERSION_REGEX.find(message)
            val vendorMatch = VENDOR_REGEX.find(message)
            val platformMatch = PLATFORM_REGEX.find(message)

            if (versionMatch != null) {
                val version = versionMatch.groupValues[1].toIntOrNull()
                val vendor = vendorMatch?.groupValues?.get(1)?.trim()?.takeIf { it != "any vendor" }
                val platform = platformMatch?.groupValues?.get(1)

                return ToolchainErrorInfo(
                    requiredVersion = version,
                    vendor = vendor,
                    platform = platform,
                    suggestions = buildToolchainSuggestions(version),
                )
            }
            current = current.cause
        }
        return null
    }

    private fun buildToolchainSuggestions(version: Int?): List<String> {
        val versionStr = version?.toString() ?: "<version>"
        return listOf(
            "Set groovy.gradle.javaHome in VS Code settings",
            "Add foojay-resolver plugin to settings.gradle for auto-download",
            "Set -Dorg.gradle.java.installations.paths=/path/to/jdk$versionStr",
        )
    }

    /**
     * Classifies an exception and returns a structured ResolutionStatus.Failed.
     */
    fun classifyException(t: Throwable): ResolutionStatus.Failed = when {
        isToolchainProvisioningError(t) -> {
            val info = extractToolchainErrorInfo(t)
            val message = buildString {
                append("Cannot find Java ${info?.requiredVersion ?: "toolchain"}")
                info?.platform?.let { append(" for $it") }
                append(". ")
                info?.suggestions?.firstOrNull()?.let { append(it) }
            }
            ResolutionStatus.Failed(ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED, message, t, details = info)
        }

        isJdkMismatch(t) -> {
            ResolutionStatus.Failed(
                ResolutionCodes.GRADLE_JDK_INCOMPATIBLE,
                "JDK version incompatible with Gradle. ${t.message}",
                t,
            )
        }

        isInitScriptError(t) -> {
            ResolutionStatus.Failed(
                ResolutionCodes.INIT_SCRIPT_ERROR,
                "Gradle init script error. ${t.message}",
                t,
            )
        }

        else -> {
            ResolutionStatus.Failed(
                ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED,
                t.message ?: "Unknown error during dependency resolution",
                t,
            )
        }
    }

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

    private companion object {
        private val VERSION_REGEX = Regex("""languageVersion=(\d+)""")
        private val VENDOR_REGEX = Regex("""vendor=([^,}]+)""")
        private val PLATFORM_REGEX = Regex("""\(([^)]+)\)""")
    }
}
