package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.int

/**
 * Backward compatibility command for `gls stdio`.
 * Aliases to `gls lsp stdio`.
 */
class StdioCommand : CliktCommand(name = "stdio") {
    override fun help(context: Context) = "Legacy alias for 'lsp stdio'"

    override fun run() {
        // Delegate directly to LspCommand's internal method
        LspCommand().runStdio()
    }
}

/**
 * Backward compatibility command for `gls socket <port>`.
 * Aliases to `gls lsp socket --port <port>`.
 */
class SocketCommand : CliktCommand(name = "socket") {
    override fun help(context: Context) = "Legacy alias for 'lsp socket'"

    private val port by argument()
        .int()
        .default(DEFAULT_PORT)

    override fun run() {
        // Delegate directly to LspCommand's internal method
        LspCommand().runSocket(port)
    }

    companion object {
        private const val DEFAULT_PORT = 8080
    }
}
