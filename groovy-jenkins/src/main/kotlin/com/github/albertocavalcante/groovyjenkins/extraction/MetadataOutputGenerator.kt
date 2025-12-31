package com.github.albertocavalcante.groovyjenkins.extraction

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

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Converts extracted steps to a serializable metadata structure.
     */
    fun generate(steps: List<ScannedStep>, pluginId: String? = null): ExtractedMetadata {
        val stepMap = steps
            .sortedBy { it.functionName ?: it.simpleName }
            .associate { step ->
                val key = step.functionName ?: step.simpleName.replaceFirstChar { it.lowercase() }
                key to StepMetadata(
                    className = step.className,
                    simpleName = step.simpleName,
                    functionName = step.functionName,
                    plugin = pluginId,
                    takesBlock = step.takesBlock,
                    parameters = (step.constructorParams + step.setterParams)
                        .sortedBy { it.name }
                        .associate { param ->
                            param.name to ParameterMetadata(
                                type = param.type,
                                required = param.isRequired,
                            )
                        },
                )
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
