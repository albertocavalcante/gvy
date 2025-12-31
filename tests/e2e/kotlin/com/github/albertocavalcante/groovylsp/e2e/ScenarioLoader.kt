package com.github.albertocavalcante.groovylsp.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Loads scenario files from disk and parses them using a [ScenarioParser].
 *
 * The parser is injected to allow easy switching between implementations
 * (e.g., Jackson now, kotlinx.serialization in the future).
 *
 * TODO: When kotlinx.serialization adds official YAML support, swap parser.
 *       Track: https://github.com/Kotlin/kotlinx.serialization/issues/1836
 */
class ScenarioLoader(private val parser: ScenarioParser = JacksonScenarioParser()) {
    fun loadAll(directory: Path): List<ScenarioDefinition> {
        require(Files.exists(directory)) {
            "Scenario directory does not exist: $directory"
        }

        Files.walk(directory, Integer.MAX_VALUE)
            .use { stream ->
                return stream
                    .filter { path -> path.isRegularFile() && path.extension in setOf("yml", "yaml") }
                    // Convention: files prefixed with _ are disabled scenarios (e.g., _discover-tests.yaml)
                    .filter { path -> !path.fileName.toString().startsWith("_") }
                    .sorted()
                    .map { path -> load(path) }
                    .collect(Collectors.toList())
            }
    }

    fun load(path: Path): ScenarioDefinition {
        val content = path.readText()
        return try {
            // The YAML file structure corresponds to Scenario (name, description, steps).
            // We wrap it in ScenarioDefinition with the source path.
            parser.parseScenarioDefinition(content).copy(source = path.toString())
        } catch (ex: ScenarioParseException) {
            throw IllegalStateException("Failed to load scenario file: $path", ex)
        }
    }
}
