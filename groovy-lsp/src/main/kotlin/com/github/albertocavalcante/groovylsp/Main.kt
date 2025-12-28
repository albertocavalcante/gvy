package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.albertocavalcante.groovylsp.cli.CheckCommand
import com.github.albertocavalcante.groovylsp.cli.ExecuteCommand
import com.github.albertocavalcante.groovylsp.cli.FormatCommand
import com.github.albertocavalcante.groovylsp.cli.GlsCommand
import com.github.albertocavalcante.groovylsp.cli.LspCommand
import com.github.albertocavalcante.groovylsp.cli.VersionCommand

/**
 * Entry point for the Groovy Language Server CLI.
 *
 * Uses Clikt for declarative command structure with Mordant for ANSI color support.
 * Supports the following subcommands:
 * - lsp: Run the language server (default)
 * - format: Format Groovy files
 * - check: Run diagnostics
 * - execute: Execute custom LSP commands
 * - version: Print version
 */
fun main(args: Array<String>) = GlsCommand()
    .subcommands(
        LspCommand(),
        FormatCommand(),
        CheckCommand(),
        ExecuteCommand(),
        VersionCommand(),
    )
    .main(args)
