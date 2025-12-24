package com.github.albertocavalcante.groovylsp.services

/**
 * Server status enum for the `groovy/status` notification.
 *
 * Provides status notifications during server lifecycle phases.
 */
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

/**
 * Payload for the `groovy/status` notification.
 *
 * @property status The current server status.
 * @property message Optional human-readable message with additional context.
 */
data class StatusNotification(val status: ServerStatus, val message: String? = null)
