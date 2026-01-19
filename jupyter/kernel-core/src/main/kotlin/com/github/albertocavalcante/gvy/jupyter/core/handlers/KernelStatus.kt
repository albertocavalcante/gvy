package com.github.albertocavalcante.gvy.jupyter.core.handlers

/**
 * Represents kernel execution states.
 *
 * Used to publish execution state on IOPub socket.
 */
enum class KernelStatus(val value: String) {
    BUSY("busy"),
    IDLE("idle"),
}
