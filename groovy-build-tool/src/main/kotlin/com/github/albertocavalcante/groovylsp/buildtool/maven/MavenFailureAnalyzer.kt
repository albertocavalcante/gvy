package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.AsmErrorAnalyzer
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionStatus
import org.slf4j.LoggerFactory

/**
 * Analyzes Maven build failures to classify exceptions and provide structured error information.
 *
 * This is similar to GradleFailureAnalyzer but specialized for Maven-specific error patterns.
 * It detects:
 * - ASM version errors (Groovy/JDK incompatibility)
 * - POM parsing errors
 * - Network connectivity issues
 * - Dependency resolution failures
 */
class MavenFailureAnalyzer {
    private val logger = LoggerFactory.getLogger(MavenFailureAnalyzer::class.java)
    private val asmErrorAnalyzer = AsmErrorAnalyzer()

    /**
     * Detects ASM version errors indicating Groovy/JDK incompatibility.
     *
     * These errors occur when Groovy's ASM version cannot handle class files
     * compiled with a newer JDK (e.g., Groovy 3.0.x with JDK 17+).
     *
     * Example error message:
     * "Unsupported class file major version 65"
     */
    fun isAsmVersionError(t: Throwable): Boolean = asmErrorAnalyzer.isAsmError(t)

    /**
     * Detects POM parsing errors.
     *
     * These errors occur when Maven cannot parse the pom.xml file due to:
     * - Invalid XML syntax
     * - Invalid POM structure
     * - Missing required elements
     *
     * Example error messages:
     * - "ModelBuildingException: Failed to parse POM"
     * - "Failed to parse POM at /path/to/pom.xml"
     * - "Non-parseable POM"
     */
    fun isPomParsingError(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("ModelBuildingException", ignoreCase = true) ||
            message.contains("Failed to parse POM", ignoreCase = true) ||
            message.contains("Non-parseable POM", ignoreCase = true)
    }

    /**
     * Detects network connectivity errors during dependency resolution.
     *
     * These errors occur when Maven cannot reach remote repositories due to:
     * - Network outages
     * - Firewall/proxy issues
     * - Repository downtime
     *
     * Example error messages:
     * - "Could not transfer artifact: Connection refused"
     * - "Could not GET https://repo.maven.apache.org/: Connection timed out"
     * - "Unable to access jarfile"
     */
    fun isConnectivityError(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("Could not transfer artifact", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("Connection timed out", ignoreCase = true) ||
            message.contains("Could not GET", ignoreCase = true) ||
            message.contains("UnknownHostException", ignoreCase = true)
    }

    /**
     * Detects dependency resolution errors.
     *
     * These errors occur when Maven cannot resolve project dependencies due to:
     * - Missing artifacts in repositories
     * - Version conflicts
     * - Invalid dependency coordinates
     *
     * Example error messages:
     * - "Could not resolve dependencies for project"
     * - "Could not find artifact org.example:missing:jar:1.0"
     * - "Failed to collect dependencies"
     */
    fun isDependencyResolutionError(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("Could not resolve dependencies", ignoreCase = true) ||
            message.contains("Could not find artifact", ignoreCase = true) ||
            message.contains("Failed to collect dependencies", ignoreCase = true) ||
            message.contains("Unresolvable build extension", ignoreCase = true)
    }

    /**
     * Classifies a Maven exception and returns structured failure information.
     *
     * Priority order:
     * 1. ASM version errors (highest priority - indicates Groovy/JDK incompatibility)
     * 2. POM parsing errors
     * 3. Connectivity errors
     * 4. Dependency resolution errors
     * 5. Generic failure (default)
     *
     * @param t The exception to classify.
     * @param groovyVersion Optional Groovy version for enhanced error messages.
     * @return Structured ResolutionStatus.Failed with appropriate error code and details.
     */
    fun classifyException(t: Throwable, groovyVersion: String?): ResolutionStatus.Failed {
        logger.debug("Classifying Maven exception: ${t.javaClass.simpleName}: ${t.message}")

        return when {
            isAsmVersionError(t) -> {
                val asmInfo = asmErrorAnalyzer.analyze(t)
                val message = buildString {
                    append("Groovy/JDK incompatibility detected. ")
                    if (groovyVersion != null) {
                        append("Groovy $groovyVersion ")
                    }
                    if (asmInfo != null) {
                        val jdkVersionStr = asmInfo.jdkVersion?.toString() ?: "version ${asmInfo.majorVersion}"
                        append("cannot handle class files from JDK $jdkVersionStr. ")
                        append(asmInfo.suggestions.firstOrNull() ?: "")
                    } else {
                        append("ASM version too old for current JDK.")
                    }
                }
                ResolutionStatus.Failed(
                    ResolutionCodes.GROOVY_JDK_INCOMPATIBLE,
                    message,
                    t,
                    details = asmInfo,
                )
            }

            isPomParsingError(t) -> {
                ResolutionStatus.Failed(
                    ResolutionCodes.POM_PARSING_FAILED,
                    "Failed to parse POM file. Check for XML syntax errors. ${t.message ?: ""}",
                    t,
                )
            }

            isConnectivityError(t) -> {
                ResolutionStatus.Failed(
                    ResolutionCodes.CONNECTIVITY_ERROR,
                    "Network connectivity error. Check internet connection and repository URLs. ${t.message ?: ""}",
                    t,
                )
            }

            isDependencyResolutionError(t) -> {
                ResolutionStatus.Failed(
                    ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED,
                    "Maven dependency resolution failed. ${t.message ?: ""}",
                    t,
                )
            }

            else -> {
                ResolutionStatus.Failed(
                    ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED,
                    t.message ?: "Unknown Maven build error",
                    t,
                )
            }
        }
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
