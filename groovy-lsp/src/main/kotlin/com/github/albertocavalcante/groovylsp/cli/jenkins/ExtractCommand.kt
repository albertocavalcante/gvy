package com.github.albertocavalcante.groovylsp.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.groovyjenkins.extraction.BytecodeScanner
import com.github.albertocavalcante.groovyjenkins.extraction.MetadataOutputGenerator
import com.github.albertocavalcante.groovyjenkins.extraction.PluginDownloader
import com.github.albertocavalcante.groovyjenkins.extraction.PluginSpec
import com.github.albertocavalcante.groovyjenkins.extraction.PluginsParser
import com.github.albertocavalcante.groovyjenkins.extraction.ScannedStep
import kotlinx.coroutines.CancellationException
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

        ensureDirectoriesExist()

        val pluginPaths = downloadPlugins(plugins)
        if (pluginPaths.isEmpty()) {
            terminal.println("${terminal.theme.danger("✗")} No plugins downloaded successfully")
            return
        }

        val allSteps = scanPlugins(pluginPaths)
        writeOutput(allSteps)
    }

    private fun ensureDirectoriesExist() {
        // Ensure directories exist
        if (!outputDir.exists()) {
            outputDir.createDirectories()
            terminal.println("${terminal.theme.info("→")} Created output directory: $outputDir")
        }
        if (!cacheDir.exists()) {
            cacheDir.createDirectories()
            terminal.println("${terminal.theme.info("→")} Created cache directory: $cacheDir")
        }
    }

    private fun downloadPlugins(plugins: List<PluginSpec>): Map<String, Path> {
        // Download plugins
        terminal.println("${terminal.theme.info("→")} Downloading plugins...")
        val downloader = PluginDownloader(cacheDir)
        val pluginPaths = mutableMapOf<String, Path>()

        runBlocking {
            plugins.forEach { plugin ->
                val pluginDescriptor = "${plugin.id}:${plugin.version}"
                terminal.println(
                    "  ${terminal.theme.muted("•")} $pluginDescriptor",
                )
                runCatching { downloader.download(plugin.id, plugin.version) }
                    .onFailure { throwable ->
                        when (throwable) {
                            is CancellationException -> throw throwable
                            is Error -> throw throwable
                            else ->
                                terminal.println(
                                    "  ${terminal.theme.danger("✗")} Failed to download " +
                                        "${plugin.id}: ${throwable.message}",
                                )
                        }
                    }
                    .onSuccess { path ->
                        pluginPaths[plugin.id] = path
                    }
            }
        }

        return pluginPaths
    }

    private fun scanPlugins(pluginPaths: Map<String, Path>): List<ScannedStep> {
        // Scan bytecode
        terminal.println(
            "${terminal.theme.info("→")} Scanning ${pluginPaths.size} plugins for Step classes...",
        )
        val scanner = BytecodeScanner()
        val allSteps = mutableListOf<ScannedStep>()

        pluginPaths.forEach { (pluginId, jarPath) ->
            runCatching {
                val steps = scanner.scanJar(jarPath)
                // Associate steps with their source plugin
                steps.map { it.copy(pluginId = pluginId) }
            }
                .onFailure { throwable ->
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is Error -> throw throwable
                        else ->
                            terminal.println(
                                "  ${terminal.theme.danger("✗")} Failed to scan " +
                                    "$pluginId: ${throwable.message}",
                            )
                    }
                }
                .onSuccess { stepsWithPlugin ->
                    terminal.println("  ${terminal.theme.success("✓")} $pluginId: ${stepsWithPlugin.size} steps")
                    allSteps.addAll(stepsWithPlugin)
                }
        }

        return allSteps
    }

    private fun writeOutput(allSteps: List<ScannedStep>) {
        // Generate output
        terminal.println("${terminal.theme.info("→")} Generating metadata JSON...")
        val metadata = MetadataOutputGenerator.generate(allSteps)
        val outputPath = outputDir.resolve("jenkins-metadata.json")
        MetadataOutputGenerator.writeToFile(metadata, outputPath)

        terminal.println("${terminal.theme.success("✓")} Extracted ${allSteps.size} steps to $outputPath")
    }
}
