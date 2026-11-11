package com.github.albertocavalcante.gvy.gls.e2e

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
    /** Loads the scenarios that run: files prefixed with `_` are disabled and skipped (see [disabledHeaderProblem]). */
    fun loadAll(directory: Path): List<ScenarioDefinition> =
        scenarioFiles(directory).filter { !isDisabled(it) }.map { load(it) }

    /** Loads every scenario file, disabled ones included, so a disabled scenario cannot rot unparsed. */
    fun loadEvery(directory: Path): List<ScenarioDefinition> = scenarioFiles(directory).map { load(it) }

    fun scenarioFiles(directory: Path): List<Path> {
        require(Files.exists(directory)) {
            "Scenario directory does not exist: $directory"
        }

        Files.walk(directory, Integer.MAX_VALUE)
            .use { stream ->
                return stream
                    .filter { path -> path.isRegularFile() && path.extension in setOf("yml", "yaml") }
                    .sorted()
                    .collect(Collectors.toList())
            }
    }

    fun isDisabled(path: Path): Boolean = path.fileName.toString().startsWith("_")

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

/**
 * A disabled (`_`-prefixed) scenario must say why in a leading `# disabled: <reason or issue>` comment, so that
 * nobody has to guess whether it is safe to delete or ready to enable. Returns the problem, or `null` when fine.
 */
fun disabledHeaderProblem(text: String): String? {
    val header = text.lineSequence().takeWhile { it.isBlank() || it.startsWith("#") }
    val reason = header.firstNotNullOfOrNull { Regex("^#\\s*disabled:\\s*(\\S.*)$").find(it)?.groupValues?.get(1) }
    return if (reason == null) "missing a leading '# disabled: <reason or issue>' comment" else null
}
