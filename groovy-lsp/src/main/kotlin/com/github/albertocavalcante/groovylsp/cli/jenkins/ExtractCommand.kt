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
import com.github.albertocavalcante.groovyjenkins.extraction.BytecodeScanner
import com.github.albertocavalcante.groovyjenkins.extraction.MetadataOutputGenerator
import com.github.albertocavalcante.groovyjenkins.extraction.PluginDownloader
import com.github.albertocavalcante.groovyjenkins.extraction.PluginsParser
import com.github.albertocavalcante.groovyjenkins.extraction.ScannedStep
import kotlinx.coroutines.runBlocking
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
 *   gls jenkins extract -p plugins.txt -o ./metadata --cache-dir ./cache
 */
class ExtractCommand : CliktCommand(name = "extract") {

    private val terminal by requireObject<Terminal>()

    private val pluginsTxt by option("--plugins-txt", "-p", help = "Path to plugins.txt")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val outputDir by option("--output-dir", "-o", help = "Output directory for JSON")
        .path(canBeFile = false)
        .default(Path.of("metadata"))

    private val cacheDir by option("--cache-dir", "-c", help = "Cache directory for downloaded plugins")
        .path(canBeFile = false)
        .default(Path.of("cache"))

    override fun run() {
        terminal.println("${terminal.theme.info("→")} Reading plugins.txt: $pluginsTxt")

        val plugins = PluginsParser.parse(pluginsTxt)
        terminal.println("${terminal.theme.info("→")} Found ${plugins.size} plugins")

        if (plugins.isEmpty()) {
            terminal.println("${terminal.theme.warning("⚠")} No plugins found in $pluginsTxt")
            return
        }

        // Ensure directories exist
        if (!outputDir.exists()) {
            outputDir.createDirectories()
            terminal.println("${terminal.theme.info("→")} Created output directory: $outputDir")
        }
        if (!cacheDir.exists()) {
            cacheDir.createDirectories()
            terminal.println("${terminal.theme.info("→")} Created cache directory: $cacheDir")
        }

        // Download plugins
        terminal.println("${terminal.theme.info("→")} Downloading plugins...")
        val downloader = PluginDownloader(cacheDir)
        val pluginPaths = mutableMapOf<String, Path>()

        runBlocking {
            plugins.forEach { plugin ->
                try {
                    terminal.println("  ${terminal.theme.muted("•")} ${plugin.id}:${plugin.version}")
                    val path = downloader.download(plugin.id, plugin.version)
                    pluginPaths[plugin.id] = path
                } catch (e: Exception) {
                    terminal.println("  ${terminal.theme.danger("✗")} Failed to download ${plugin.id}: ${e.message}")
                }
            }
        }

        if (pluginPaths.isEmpty()) {
            terminal.println("${terminal.theme.danger("✗")} No plugins downloaded successfully")
            return
        }

        // Scan bytecode
        terminal.println("${terminal.theme.info("→")} Scanning ${pluginPaths.size} plugins for Step classes...")
        val scanner = BytecodeScanner()
        val allSteps = mutableListOf<ScannedStep>()

        pluginPaths.forEach { (pluginId, jarPath) ->
            try {
                val steps = scanner.scanJar(jarPath)
                // Associate steps with their source plugin
                val stepsWithPlugin = steps.map { it.copy(pluginId = pluginId) }
                terminal.println("  ${terminal.theme.success("✓")} $pluginId: ${steps.size} steps")
                allSteps.addAll(stepsWithPlugin)
            } catch (e: Exception) {
                terminal.println("  ${terminal.theme.danger("✗")} Failed to scan $pluginId: ${e.message}")
            }
        }

        // Generate output
        terminal.println("${terminal.theme.info("→")} Generating metadata JSON...")
        val metadata = MetadataOutputGenerator.generate(allSteps)
        val outputPath = outputDir.resolve("jenkins-metadata.json")
        MetadataOutputGenerator.writeToFile(metadata, outputPath)

        terminal.println("${terminal.theme.success("✓")} Extracted ${allSteps.size} steps to $outputPath")
    }
}
