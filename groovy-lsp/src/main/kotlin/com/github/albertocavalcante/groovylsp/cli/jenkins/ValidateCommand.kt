package com.github.albertocavalcante.groovylsp.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.groovyjenkins.extraction.PluginsParser

/**
 * Validates the syntax of a plugins.txt file.
 *
 * This command checks:
 * - Each line follows the format: plugin-id:version
 * - No duplicate plugin IDs
 * - Valid plugin ID format (no spaces, special chars)
 *
 * Usage:
 *   gls jenkins validate plugins.txt
 */
class ValidateCommand : CliktCommand(name = "validate") {

    private val pluginsTxt by argument("plugins-txt")
        .path(mustExist = true)

    private val terminal by requireObject<Terminal>()

    override fun help(context: Context) = "Validate plugins.txt file syntax"

    override fun run() {
        terminal.println("${terminal.theme.info("→")} Validating: $pluginsTxt")

        try {
            val plugins = PluginsParser.parse(pluginsTxt)

            // Check for duplicates
            val duplicates = plugins.groupBy { it.id }
                .filter { it.value.size > 1 }
                .keys

            if (duplicates.isNotEmpty()) {
                terminal.println("${terminal.theme.warning("⚠")} Duplicate plugins found:")
                duplicates.forEach { id ->
                    terminal.println("  ${terminal.theme.danger("✗")} $id")
                }
            }

            // Report summary
            terminal.println("${terminal.theme.success("✓")} Valid: ${plugins.size} plugins")
            plugins.forEach { plugin ->
                terminal.println("  ${terminal.theme.muted("•")} ${plugin.id}:${plugin.version}")
            }
        } catch (e: IllegalArgumentException) {
            terminal.println("${terminal.theme.danger("✗")} Invalid format: ${e.message}")
            throw e
        }
    }
}
