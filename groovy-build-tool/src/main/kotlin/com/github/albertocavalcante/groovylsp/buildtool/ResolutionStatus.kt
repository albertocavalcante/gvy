package com.github.albertocavalcante.groovylsp.buildtool

/**
 * Represents the result of a dependency resolution operation.
 */
sealed interface ResolutionStatus {
    /**
     * Represents a successful resolution.
     */
    data class Success(val message: String = "Resolution succeeded") : ResolutionStatus

    /**
     * Represents a failed resolution with error details.
     */
    data class Failed(val code: String, val message: String, val cause: Throwable? = null) : ResolutionStatus
}

/**
 * Standard resolution error codes.
 */
object ResolutionCodes {
    /** Gradle toolchain provisioning failed (cannot find required JDK). */
    const val TOOLCHAIN_PROVISIONING_FAILED = "TOOLCHAIN_PROVISIONING_FAILED"

    /** JDK version incompatible with Gradle version. */
    const val GRADLE_JDK_INCOMPATIBLE = "GRADLE_JDK_INCOMPATIBLE"

    /** Gradle init script error. */
    const val INIT_SCRIPT_ERROR = "INIT_SCRIPT_ERROR"

    /** Generic dependency resolution failure. */
    const val DEPENDENCY_RESOLUTION_FAILED = "DEPENDENCY_RESOLUTION_FAILED"
}
