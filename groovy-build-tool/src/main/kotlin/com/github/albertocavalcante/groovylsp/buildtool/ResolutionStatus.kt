package com.github.albertocavalcante.groovylsp.buildtool

/**
 * Represents the outcome of a workspace dependency resolution operation.
 */
sealed interface ResolutionStatus {
    /** Resolution completed successfully. */
    data object Success : ResolutionStatus

    /** Resolution completed with warnings (e.g., 0 JARs for project with declared deps). */
    data class Warning(val code: String, val message: String) : ResolutionStatus

    /** Resolution failed with an error. */
    data class Failed(val code: String, val message: String, val cause: Throwable? = null, val details: Any? = null) :
        ResolutionStatus
}

/**
 * Well-known resolution status codes for structured error handling.
 */
object ResolutionCodes {
    /** Project has zero dependencies resolved (warning state). */
    const val ZERO_DEPENDENCIES = "ZERO_DEPENDENCIES"

    /** Gradle toolchain provisioning failed (cannot find required JDK). */
    const val TOOLCHAIN_PROVISIONING_FAILED = "TOOLCHAIN_PROVISIONING_FAILED"

    /** JDK version incompatible with Gradle version. */
    const val GRADLE_JDK_INCOMPATIBLE = "GRADLE_JDK_INCOMPATIBLE"

    /** Gradle init script error. */
    const val INIT_SCRIPT_ERROR = "INIT_SCRIPT_ERROR"

    /** Generic dependency resolution failure. */
    const val DEPENDENCY_RESOLUTION_FAILED = "DEPENDENCY_RESOLUTION_FAILED"
}

/**
 * Extension function to check if a status is usable (Success or Warning).
 */
val ResolutionStatus.isUsable: Boolean
    get() = this is ResolutionStatus.Success || this is ResolutionStatus.Warning
