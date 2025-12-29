package com.github.albertocavalcante.groovylsp.services

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
 */
data class StatusNotification(
    val health: Health = Health.Ok,
    val quiescent: Boolean = true,
    val message: String? = null,
    val filesIndexed: Int? = null,
    val filesTotal: Int? = null,
)

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
