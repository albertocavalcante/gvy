package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Root command for the Groovy Language Server CLI.
 *
 * This command serves as the entry point and delegates to subcommands.
 * When invoked without a subcommand, defaults to running the LSP in stdio mode.
 *
 * Supports NO_COLOR environment variable (https://no-color.org/) and --no-color flag.
 */
class GlsCommand : CliktCommand(name = "gls") {
    /**
     * Disable colored output. Respects NO_COLOR environment variable by default.
     * See: https://no-color.org/
     */
    private val noColor by option("--no-color", help = "Disable colored output")
        .flag()

    override fun help(context: Context) = "Groovy Language Server - A modern LSP implementation for Groovy development"

    override val invokeWithoutSubcommand = true

    init {
        context {
            helpFormatter = { ctx ->
                GlsHelpFormatter(ctx)
            }
        }
        subcommands(
            LspCommand(),
            CheckCommand(),
            ExecuteCommand(),
            FormatCommand(),
            VersionCommand(),
            StdioCommand(),
            SocketCommand(),
        )
    }

    override fun run() {
        // Create terminal with appropriate ANSI level based on --no-color flag
        // Note: Mordant already respects NO_COLOR env var by default
        val terminal = if (noColor) {
            Terminal(ansiLevel = AnsiLevel.NONE)
        } else {
            Terminal() // Respects NO_COLOR env var automatically
        }

        // Store terminal in context for subcommands to access
        currentContext.findOrSetObject { terminal }

        // If no subcommand is provided, default to LSP stdio mode
        if (currentContext.invokedSubcommand == null) {
            // Note: Do NOT print to stdout here - it would corrupt LSP JSON-RPC protocol
            // in stdio mode since stdout is used for LSP communication.
            // Call runStdio() directly to avoid going through Clikt argument parsing
            // which would create a new context and lose our Terminal configuration.
            LspCommand().runStdio()
        }
    }
}
