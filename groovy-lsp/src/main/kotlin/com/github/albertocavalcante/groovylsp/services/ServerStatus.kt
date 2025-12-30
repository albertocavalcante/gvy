package com.github.albertocavalcante.groovylsp.services

// TODO: Migrate from Gson to kotlinx.serialization for better Kotlin-native serialization support.
//       This would allow using @Serializable annotations and remove the Gson dependency.
//       See: https://kotlinlang.org/docs/serialization.html
import com.google.gson.annotations.SerializedName

/**
 * Server health status for the `groovy/status` notification.
 *
 * Follows rust-analyzer's experimental/serverStatus pattern.
 */
enum class Health {
    /** Server is fully functional. */
    @SerializedName("ok")
    Ok,

    /** Server is partially functional (e.g., some missing dependencies). */
    @SerializedName("warning")
    Warning,

    /** Server is not functional (e.g., fatal build configuration problem). */
    @SerializedName("error")
    Error,
}

/**
 * Payload for the `groovy/status` notification.
 *
 * This notification is sent from server to client to provide persistent status.
 * It is similar to `showMessage`, but is intended for status rather than point-in-time events.
 *
 * Based on rust-analyzer's `experimental/serverStatus` notification.
 *
 * @property health The server functional state (ok, warning, error).
 * @property quiescent Whether there is any pending background work.
 *                     `false` means the server is actively processing (indexing, resolving deps, etc.).
 *                     `true` means the server is idle and ready for requests.
 * @property message Optional human-readable message with additional context.
 * @property filesIndexed Current number of files indexed (for progress display).
 * @property filesTotal Total number of files to index (for progress display).
 * @property errorCode Machine-readable error code for structured error handling (e.g., "GRADLE_JDK_INCOMPATIBLE").
 * @property errorDetails Structured error information for actionable error display.
 */
data class StatusNotification(
    val health: Health = Health.Ok,
    val quiescent: Boolean = true,
    val message: String? = null,
    val filesIndexed: Int? = null,
    val filesTotal: Int? = null,
    val errorCode: String? = null,
    val errorDetails: ErrorDetails? = null,
)

/**
 * Structured error details for enhanced error reporting.
 *
 * This is a polymorphic error model that can represent different error categories.
 * Clients can use the `type` discriminator field to handle specific error types.
 *
 * Design rationale: Using a sealed interface with subtypes allows:
 * - Type-safe error handling on both server and client
 * - Extensibility for new error categories without breaking existing code
 * - Specific fields per error type without nullable field soup
 * - Clean JSON serialization with type discriminator
 *
 * @property type Discriminator field for polymorphic JSON serialization.
 * @property suggestions List of actionable suggestions to resolve the error.
 */
sealed interface ErrorDetails {
    val type: String
    val suggestions: List<String>
}

/**
 * Error: Gradle version is incompatible with the running JDK.
 *
 * This is a common error when the LSP runs on a newer JDK than the project's
 * Gradle wrapper supports. Actionable via Gradle upgrade or JDK downgrade.
 */
data class GradleJdkIncompatibleError(
    val gradleVersion: String,
    val jdkVersion: Int,
    val minGradleVersion: String,
    val maxJdkVersion: String?,
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = "GRADLE_JDK_INCOMPATIBLE"
}

/**
 * Error: No build tool (Gradle/Maven) detected in the workspace.
 *
 * The LSP is running in syntax-only mode without classpath resolution.
 */
data class NoBuildToolError(
    val searchedPaths: List<String> = emptyList(),
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = "NO_BUILD_TOOL"
}

/**
 * Error: Dependency resolution failed for a generic reason.
 *
 * Catch-all for build tool failures that don't fit other categories.
 */
data class DependencyResolutionError(
    val buildTool: String,
    val cause: String?,
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = "DEPENDENCY_RESOLUTION_FAILED"
}

/**
 * Error: Java runtime not found or invalid.
 */
data class JavaNotFoundError(
    val configuredPath: String?,
    val searchedLocations: List<String> = emptyList(),
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = "JAVA_NOT_FOUND"
}

/**
 * Generic error details for unclassified errors.
 *
 * Use this when a more specific error type doesn't exist yet.
 */
data class GenericError(
    val errorCode: String,
    val details: Map<String, String> = emptyMap(),
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = errorCode
}

/**
 * Legacy server status enum for backwards compatibility.
 *
 * @deprecated Use [Health] and [StatusNotification] instead.
 */
@Deprecated("Use Health enum and StatusNotification with quiescent field", ReplaceWith("Health"))
enum class ServerStatus {
    /** Server is initializing (after `initialize` but before ready). */
    Starting,

    /** Server is fully ready to handle requests. */
    Ready,

    /** Server is performing background indexing. */
    Indexing,

    /** An error occurred during initialization. */
    Error,
}
