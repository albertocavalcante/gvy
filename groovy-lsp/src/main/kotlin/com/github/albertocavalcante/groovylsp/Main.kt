package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.main
import com.github.albertocavalcante.groovylsp.cli.GlsCommand

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
fun main(args: Array<String>) = GlsCommand().main(args)
