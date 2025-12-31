package com.github.albertocavalcante.groovyjenkins.extraction

import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Represents a Jenkins plugin specification from plugins.txt.
 *
 * @property id The plugin ID (e.g., "workflow-basic-steps")
 * @property version The plugin version (e.g., "1058.v1" or "latest")
 */
data class PluginSpec(val id: String, val version: String) {
    /**
     * Returns a cache-friendly string representation.
     */
    fun toCacheKey(): String = "$id:$version"
}

/**
 * Parser for Jenkins plugins.txt files.
 *
 * The format is:
 * - One plugin per line: plugin-id:version
 * - Lines starting with # are comments
 * - Empty lines are ignored
 * - Inline comments are supported: plugin-id:version # comment
 *
 * This parser is deterministic - the same input always produces the same output
 * in the same order.
 */
object PluginsParser {

    /**
     * Parses a plugins.txt file and returns a list of plugin specifications.
     *
     * @param path Path to the plugins.txt file
     * @return List of PluginSpec in the order they appear in the file
     */
    fun parse(path: Path): List<PluginSpec> = path.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it.startsWith("#") }
        .map { line -> parseLine(line) }

    private fun parseLine(line: String): PluginSpec {
        // Handle inline comments: "plugin:version # comment"
        val effectiveLine = line.substringBefore('#').trim()

        val parts = effectiveLine.split(':')
        require(parts.size == 2) {
            "Invalid plugin format: '$line'. Expected format: plugin-id:version"
        }

        return PluginSpec(
            id = parts[0].trim(),
            version = parts[1].trim(),
        )
    }
}
