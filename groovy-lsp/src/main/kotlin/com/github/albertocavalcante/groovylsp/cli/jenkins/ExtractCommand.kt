package com.github.albertocavalcante.groovylsp.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.groovyjenkins.extraction.PluginsParser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Extracts Jenkins step metadata from plugin JARs using static analysis.
 *
 * This command:
 * 1. Parses a plugins.txt file to determine which plugins to analyze
 * 2. Downloads plugins from Jenkins Update Center (if not cached)
 * 3. Scans plugin bytecode to extract Step metadata
 * 4. Outputs JSON metadata that can be used by the LSP
 *
 * Usage:
 *   gls jenkins extract --plugins-txt plugins.txt --output-dir ./metadata
 *   gls jenkins extract -p plugins.txt -o ./metadata --force
 */
class ExtractCommand : CliktCommand(name = "extract") {

    private val pluginsTxt by option("--plugins-txt", "-p")
        .path(mustExist = true)
        .required()

    private val outputDir by option("--output-dir", "-o")
        .path()
        .default(Path.of("jenkins-metadata"))

    private val cacheDir by option("--cache-dir")
        .path()
        .default(Path.of(System.getProperty("user.home"), ".gls", "jenkins-cache"))

    private val force by option("--force", "-f")
        .flag()

    private val terminal by requireObject<Terminal>()

    override fun help(context: Context) = "Extract Jenkins step metadata from plugin bytecode"

    override fun run() {
        terminal.println("${terminal.theme.info("→")} Reading plugins.txt: $pluginsTxt")

        val plugins = PluginsParser.parse(pluginsTxt)
        terminal.println("${terminal.theme.info("→")} Found ${plugins.size} plugins")

        if (plugins.isEmpty()) {
            terminal.println("${terminal.theme.warning("⚠")} No plugins found in $pluginsTxt")
            return
        }

        // Ensure output directory exists
        if (!outputDir.exists()) {
            outputDir.createDirectories()
            terminal.println("${terminal.theme.info("→")} Created output directory: $outputDir")
        }

        // TODO: Implement plugin downloading and bytecode scanning
        // For now, just list the plugins we would process
        plugins.forEach { plugin ->
            terminal.println("  ${terminal.theme.muted("•")} ${plugin.id}:${plugin.version}")
        }

        terminal.println("${terminal.theme.success("✓")} Parsed ${plugins.size} plugins")
        terminal.println("${terminal.theme.warning("⚠")} Full extraction not yet implemented")
    }
}
