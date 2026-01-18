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
 * Error: Gradle toolchain provisioning failed.
 *
 * This occurs when Gradle uses Java Toolchains but cannot find or download
 * a JDK matching the project requirements.
 *
 * Common causes:
 * - Required JDK version not installed locally
 * - Toolchain download repositories not configured (foojay plugin missing)
 * - org.gradle.java.installations.paths not pointing to JDK locations
 */
data class ToolchainProvisioningError(val requiredVersion: Int?, val vendor: String?, val platform: String?) :
    ErrorDetails {
    override val type: String = "TOOLCHAIN_PROVISIONING_FAILED"
    override val suggestions: List<String>
        get() {
            val installSuggestion = if (requiredVersion != null) {
                "Install the required JDK: sdk install java $requiredVersion-tem"
            } else {
                "Install the required JDK: sdk install java <version>-tem"
            }
            return listOf(
                installSuggestion,
                "Add to settings.gradle: id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'",
                "Or set GRADLE_OPTS: -Dorg.gradle.java.installations.paths=/path/to/jdk",
                "Or add to gradle.properties: org.gradle.java.installations.auto-download=true",
            )
        }
}

/**
 * Error: Groovy version is incompatible with the running JDK.
 *
 * This occurs when an older Groovy version (e.g., 3.0.x) is compiled or run
 * with a newer JDK that uses bytecode versions not supported by the Groovy
 * ASM version. Common with Groovy 3.0.x on JDK 17+.
 *
 * @property groovyVersion The detected Groovy version (e.g., "3.0.9").
 * @property jdkVersion The running JDK version (e.g., 21).
 * @property classFileMajorVersion The class file major version that caused the error (e.g., 65 for JDK 21).
 * @property minGroovyVersion The minimum Groovy version required for this JDK (e.g., "4.0.0").
 * @property maxJdkVersion The maximum JDK version supported by the current Groovy version (e.g., "16").
 */
data class GroovyJdkIncompatibleError(
    val groovyVersion: String?,
    val jdkVersion: Int,
    val classFileMajorVersion: Int,
    val minGroovyVersion: String?,
    val maxJdkVersion: String?,
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = "GROOVY_JDK_INCOMPATIBLE"
}

/**
 * Error: Project's configured JDK requirement doesn't match running JDK.
 *
 * This is detected at startup by parsing build configuration files (pom.xml, build.gradle)
 * and comparing against the JDK running the LSP.
 *
 * This error is raised when the running JDK is OLDER than what the project requires.
 * For cases where the running JDK is NEWER, a warning is issued instead.
 *
 * @property runningJdkVersion The JDK version running the LSP (e.g., 11).
 * @property requiredJdkVersion The JDK version required by the project (e.g., 17).
 * @property configurationSource Where the requirement was found (e.g., "maven-compiler-plugin").
 */
data class ProjectJdkIncompatibleError(
    val runningJdkVersion: Int,
    val requiredJdkVersion: Int,
    val configurationSource: String,
    override val suggestions: List<String> = emptyList(),
) : ErrorDetails {
    override val type: String = "PROJECT_JDK_INCOMPATIBLE"
}

/**
 * Warning: LSP is running a newer JDK than the project target.
 *
 * This is non-fatal but may cause "Unsupported class file major version" errors
 * when dependencies are compiled with the LSP's JDK version but the project
 * expects an older bytecode version.
 *
 * Unlike [ProjectJdkIncompatibleError] (which is fatal when LSP is OLDER),
 * this is a warning when LSP is NEWER - we continue but alert the user.
 *
 * @property runningJdkVersion The JDK version running the LSP (e.g., 25).
 * @property targetJdkVersion The JDK version the project targets (e.g., 8).
 * @property configurationSource Where the target was found (e.g., "build.gradle sourceCompatibility").
 */
data class ProjectJdkNewerWarning(
    val runningJdkVersion: Int,
    val targetJdkVersion: Int,
    val configurationSource: String,
    override val suggestions: List<String> = listOf(
        "Configure groovy.java.home to use JDK $targetJdkVersion",
        "Or update the project's target compatibility to match the LSP JDK",
    ),
) : ErrorDetails {
    override val type: String = "PROJECT_JDK_NEWER_WARNING"
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
