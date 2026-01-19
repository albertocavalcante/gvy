package com.github.albertocavalcante.gvy.build

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

    /** Groovy version incompatible with JDK version. */
    const val GROOVY_JDK_INCOMPATIBLE = "GROOVY_JDK_INCOMPATIBLE"

    /** POM parsing failed. */
    const val POM_PARSING_FAILED = "POM_PARSING_FAILED"

    /** Network connectivity error during dependency resolution. */
    const val CONNECTIVITY_ERROR = "CONNECTIVITY_ERROR"

    /** Generic dependency resolution failure. */
    const val DEPENDENCY_RESOLUTION_FAILED = "DEPENDENCY_RESOLUTION_FAILED"

    /** Project JDK requirement cannot be met by running JDK (running < required). */
    const val PROJECT_JDK_INCOMPATIBLE = "PROJECT_JDK_INCOMPATIBLE"

    /** Warning: Running JDK is significantly newer than project target (potential bytecode issues). */
    const val PROJECT_JDK_MISMATCH_WARNING = "PROJECT_JDK_MISMATCH_WARNING"
}

/**
 * Extension function to check if a status is usable (Success or Warning).
 */
val ResolutionStatus.isUsable: Boolean
    get() = this is ResolutionStatus.Success || this is ResolutionStatus.Warning
