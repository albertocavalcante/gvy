package com.github.albertocavalcante.gvy.jenkins.extraction

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates JSON output from extracted Jenkins step metadata.
 *
 * The output format is designed for:
 * 1. Determinism - sorted keys, consistent ordering
 * 2. Readability - pretty printed, clear structure
 * 3. Golden file testing - structural comparison
 */
object MetadataOutputGenerator {

    private val logger = KotlinLogging.logger {}

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    /**
     * Converts extracted steps to a serializable metadata structure.
     */
    fun generate(steps: List<ScannedStep>): ExtractedMetadata {
        val sortedSteps = steps.sortedBy { it.functionName ?: it.simpleName }
        val stepMap = buildMap {
            sortedSteps.forEach { step ->
                val key = step.functionName ?: step.simpleName.replaceFirstChar { it.lowercase() }
                if (containsKey(key)) {
                    logger.warn {
                        "Duplicate key '$key' for class ${step.className}. Previous entry will be overwritten."
                    }
                }
                put(
                    key,
                    StepMetadata(
                        className = step.className,
                        simpleName = step.simpleName,
                        functionName = step.functionName,
                        plugin = step.pluginId,
                        takesBlock = step.takesBlock,
                        parameters = (step.constructorParams + step.setterParams)
                            .sortedBy { it.name }
                            .fold(mutableMapOf<String, ParameterMetadata>()) { acc, param ->
                                val existing = acc[param.name]
                                val shouldReplace = existing == null || (!existing.required && param.isRequired)

                                if (shouldReplace) {
                                    acc[param.name] = ParameterMetadata(
                                        type = param.type,
                                        required = param.isRequired,
                                    )
                                }
                                acc
                            },
                    ),
                )
            }
        }

        return ExtractedMetadata(
            version = "1.0",
            generatedAt = null, // Omit for determinism in tests
            steps = stepMap,
        )
    }

    /**
     * Writes metadata to a JSON file.
     */
    fun writeToFile(metadata: ExtractedMetadata, outputPath: Path) {
        outputPath.parent?.let { java.nio.file.Files.createDirectories(it) }
        val jsonString = json.encodeToString(metadata)
        outputPath.writeText(jsonString)
    }

    /**
     * Converts metadata to JSON string.
     */
    fun toJsonString(metadata: ExtractedMetadata): String = json.encodeToString(metadata)
}

/**
 * Root structure for extracted metadata.
 */
@Serializable
data class ExtractedMetadata(
    val version: String,
    val generatedAt: String? = null,
    val steps: Map<String, StepMetadata>,
)

/**
 * Metadata for a single Jenkins step.
 */
@Serializable
data class StepMetadata(
    val className: String,
    val simpleName: String,
    val functionName: String?,
    val plugin: String?,
    val takesBlock: Boolean,
    val parameters: Map<String, ParameterMetadata>,
)

/**
 * Metadata for a step parameter.
 */
@Serializable
data class ParameterMetadata(val type: String, val required: Boolean)
