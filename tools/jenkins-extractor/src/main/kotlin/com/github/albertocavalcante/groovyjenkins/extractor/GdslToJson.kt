package com.github.albertocavalcante.groovyjenkins.extractor

import com.github.albertocavalcante.groovygdsl.GdslExecutor
import com.github.albertocavalcante.groovygdsl.model.MethodDescriptor
import com.github.albertocavalcante.groovygdsl.model.ParameterDescriptor
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.ExtractedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.ExtractedParameter
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.ExtractedStep
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.ExtractionInfo
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.PluginInfo
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.PluginMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("GdslToJson")

private const val REQUIRED_ARGS_COUNT = 5
private const val USAGE_EXIT_CODE = 1
private const val ARG_INDEX_GDSL_FILE = 0
private const val ARG_INDEX_OUTPUT_DIR = 1
private const val ARG_INDEX_JENKINS_VERSION = 2
private const val ARG_INDEX_PLUGIN_ID = 3
private const val ARG_INDEX_PLUGIN_VERSION = 4
private const val ARG_INDEX_PLUGIN_DISPLAY_NAME = 5
private const val OBJECT_TYPE = "java.lang.Object"
private const val SIMPLE_OBJECT_TYPE = "Object"

data class ExtractionInputs(
    val jenkinsVersion: String,
    val gdslSha256: String,
    val pluginsManifestSha256: String,
    val extractedAt: String? = null,
)

private data class CliArgs(
    val gdslFile: Path,
    val outputDir: Path,
    val jenkinsVersion: String,
    val pluginId: String,
    val pluginVersion: String,
    val pluginDisplayName: String? = null,
)

/**
 * JSON encoder with pretty printing for human-readable output.
 */
@OptIn(ExperimentalSerializationApi::class)
private val jsonEncoder = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Simplifies fully-qualified Java type names to their simple form.
 *
 * Converts `java.lang.String` → `String`, but preserves custom types like
 * `org.jenkinsci.plugins.workflow.cps.EnvActionImpl`.
 *
 * @param type The fully-qualified or simple type name
 * @return Simplified type name
 */
fun simplifyType(type: String): String = when {
    type.startsWith("java.lang.") -> type.substringAfterLast(".")
    else -> type
}

/**
 * Converts GDSL content to PluginMetadata.
 *
 * This function:
 * 1. Parses GDSL using GdslExecutor
 * 2. Converts methods to ExtractedStep instances
 * 3. Converts properties to ExtractedGlobalVariable instances
 * 4. Packages everything into PluginMetadata with audit trail
 *
 * @param gdslContent The GDSL script content
 * @param pluginInfo Plugin identification
 * @param jenkinsVersion Jenkins version used for extraction
 * @param gdslSha256 SHA-256 checksum of the GDSL file
 * @param pluginsManifestSha256 SHA-256 checksum of plugins.txt
 * @return PluginMetadata containing all extracted information
 */
fun convertGdslToMetadata(gdslContent: String, pluginInfo: PluginInfo, inputs: ExtractionInputs): PluginMetadata {
    logger.info("Converting GDSL to metadata for plugin: ${pluginInfo.id}")

    val executor = GdslExecutor()
    val parseResult = executor.executeAndCapture(gdslContent, "${pluginInfo.id}.gdsl")

    if (!parseResult.success) {
        logger.error("Failed to parse GDSL: ${parseResult.error}")
        throw IllegalArgumentException("GDSL parsing failed: ${parseResult.error}")
    }

    val steps = parseResult.methods
        .groupBy { it.name }
        .toSortedMap()
        .mapValues { (_, overloads) -> convertToExtractedStep(overloads) }

    // Convert properties to global variables
    val globalVariables = parseResult.properties
        .sortedBy { it.name }
        .associate { property ->
            property.name to ExtractedGlobalVariable(
                type = property.type, // Keep fully qualified for global vars
                documentation = property.documentation,
            )
        }

    // Create extraction metadata
    val extraction = ExtractionInfo(
        jenkinsVersion = inputs.jenkinsVersion,
        extractedAt = resolveExtractedAt(inputs.extractedAt),
        pluginsManifestSha256 = inputs.pluginsManifestSha256,
        gdslSha256 = inputs.gdslSha256,
        extractorVersion = "1.0.0",
    )

    logger.info("Extracted ${steps.size} steps and ${globalVariables.size} global variables")

    return PluginMetadata(
        plugin = pluginInfo,
        extraction = extraction,
        steps = steps,
        globalVariables = globalVariables,
    )
}

/**
 * Converts GDSL to PluginMetadata and writes it as JSON to a file.
 *
 * @param gdslContent The GDSL script content
 * @param pluginInfo Plugin identification
 * @param jenkinsVersion Jenkins version used for extraction
 * @param gdslSha256 SHA-256 checksum of the GDSL file
 * @param pluginsManifestSha256 SHA-256 checksum of plugins.txt
 * @param outputFile Path where JSON should be written
 */
fun writePluginMetadataJson(gdslContent: String, pluginInfo: PluginInfo, inputs: ExtractionInputs, outputFile: Path) {
    val metadata = convertGdslToMetadata(
        gdslContent = gdslContent,
        pluginInfo = pluginInfo,
        inputs = inputs,
    )

    val json = jsonEncoder.encodeToString(metadata)
    outputFile.writeText("$json\n")

    logger.info("Wrote plugin metadata to: $outputFile")
}

/**
 * Command-line entry point for GDSL to JSON conversion.
 *
 * Usage:
 *   GdslToJson <gdsl-file> <output-dir> <jenkins-version> <plugin-id> <plugin-version> [plugin-display-name]
 *
 * Example:
 *   GdslToJson jenkins.gdsl output/ 2.426.3 workflow-basic-steps 1058.v1 "Pipeline: Basic Steps"
 */
fun main(args: Array<String>) {
    val parsed = parseCliArgs(args)
    if (parsed == null) {
        System.err.println(usageText())
        exitProcess(USAGE_EXIT_CODE)
    }

    val pluginsManifestSha256 = System.getenv("PLUGINS_MANIFEST_SHA256")
        ?: error("Missing required env var: PLUGINS_MANIFEST_SHA256")

    val gdslSha256 = System.getenv("GDSL_SHA256") ?: sha256Hex(parsed.gdslFile.readBytes())

    parsed.outputDir.createDirectories()

    val outputFile = parsed.outputDir.resolve("${parsed.pluginId}.json")
    val gdslContent = parsed.gdslFile.readText()

    val inputs = ExtractionInputs(
        jenkinsVersion = parsed.jenkinsVersion,
        gdslSha256 = gdslSha256,
        pluginsManifestSha256 = pluginsManifestSha256,
        extractedAt = System.getenv("EXTRACTED_AT"),
    )

    writePluginMetadataJson(
        gdslContent = gdslContent,
        pluginInfo = PluginInfo(
            id = parsed.pluginId,
            version = parsed.pluginVersion,
            displayName = parsed.pluginDisplayName,
        ),
        inputs = inputs,
        outputFile = outputFile,
    )

    logger.info("Converted ${parsed.gdslFile.name} to ${outputFile.name}")
}

private fun parseCliArgs(args: Array<String>): CliArgs? {
    if (args.isEmpty()) return null

    if (args.any { it == "--help" || it == "-h" }) return null

    // NOTE: This CLI supports both positional args and `--key=value` / `--key value` flags.
    // The flag form is more resilient to argument reordering.
    // TODO: Replace this with a dedicated CLI library (e.g. Clikt or kotlinx-cli) once we want
    // richer validation, help generation, and subcommands.
    val hasFlags = args.any { it.startsWith("--") }
    return if (hasFlags) parseFlagArgs(args) else parsePositionalArgs(args)
}

private fun parsePositionalArgs(args: Array<String>): CliArgs? {
    if (args.size < REQUIRED_ARGS_COUNT) return null

    return CliArgs(
        gdslFile = Path.of(args[ARG_INDEX_GDSL_FILE]),
        outputDir = Path.of(args[ARG_INDEX_OUTPUT_DIR]),
        jenkinsVersion = args[ARG_INDEX_JENKINS_VERSION],
        pluginId = args[ARG_INDEX_PLUGIN_ID],
        pluginVersion = args[ARG_INDEX_PLUGIN_VERSION],
        pluginDisplayName = args.getOrNull(ARG_INDEX_PLUGIN_DISPLAY_NAME),
    )
}

private fun parseFlagArgs(args: Array<String>): CliArgs? {
    val values = mutableMapOf<String, String>()

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (!arg.startsWith("--")) {
            // Ignore positional fragments when using flags.
            i++
            continue
        } else {
            val (key, valueFromEquals) = arg.removePrefix("--").split("=", limit = 2).let { parts ->
                parts[0] to parts.getOrNull(1)
            }

            val value = valueFromEquals ?: args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            if (value == null) return null

            values[key] = value
            i += if (valueFromEquals != null) 1 else 2
        }
    }

    val requiredKeys = listOf(
        "gdsl-file",
        "output-dir",
        "jenkins-version",
        "plugin-id",
        "plugin-version",
    )

    val missingKey = requiredKeys.firstOrNull { values[it].isNullOrBlank() }
    if (missingKey != null) return null

    return CliArgs(
        gdslFile = Path.of(values.getValue("gdsl-file")),
        outputDir = Path.of(values.getValue("output-dir")),
        jenkinsVersion = values.getValue("jenkins-version"),
        pluginId = values.getValue("plugin-id"),
        pluginVersion = values.getValue("plugin-version"),
        pluginDisplayName = values["plugin-display-name"],
    )
}

private fun usageText(): String =
    """
    Usage:
      GdslToJson <gdsl-file> <output-dir> <jenkins-version> <plugin-id> <plugin-version> [plugin-display-name]
      GdslToJson --gdsl-file <path> --output-dir <dir> --jenkins-version <version> --plugin-id <id> --plugin-version <version> [--plugin-display-name <name>]

    Arguments:
      gdsl-file             Path to GDSL file
      output-dir            Output directory for JSON files
      jenkins-version       Jenkins version (e.g., 2.426.3)
      plugin-id             Plugin ID (e.g., workflow-basic-steps)
      plugin-version        Plugin version (e.g., 1058.vcb_fc1e3a_21a_9)
      plugin-display-name   Optional: Plugin display name

    Environment:
      GDSL_SHA256              SHA-256 of GDSL file (optional; computed if missing)
      PLUGINS_MANIFEST_SHA256  SHA-256 of plugins.txt (required)
      EXTRACTED_AT             Optional ISO-8601 timestamp
      SOURCE_DATE_EPOCH        Optional epoch seconds (used if EXTRACTED_AT is not set)
    """.trimIndent()

private fun resolveExtractedAt(extractedAt: String?): String {
    if (extractedAt != null) return extractedAt

    val sourceDateEpochSeconds = System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()
    if (sourceDateEpochSeconds != null) {
        return Instant.ofEpochSecond(sourceDateEpochSeconds).toString()
    }

    // NOTE: Defaulting to epoch keeps unit tests and local runs reproducible (byte-for-byte) without
    // requiring extra inputs.
    // TODO: Revisit whether we want to default to "now" for human auditing, or always require callers
    // to provide `extractedAt` (e.g. from a deterministic pipeline input).
    // TODO(#516): Replace GDSL-based extraction with static analysis of plugin JARs to get richer metadata
    // (e.g. strict types, proper closure support, mandatory vs optional params).
    return Instant.EPOCH.toString()
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { b -> "%02x".format(b) }
}

private fun convertToExtractedStep(overloads: List<MethodDescriptor>): ExtractedStep {
    val scopes = overloads.map(::determineScope).toSet()
    val scope = when {
        StepScope.NODE in scopes -> StepScope.NODE
        StepScope.STAGE in scopes -> StepScope.STAGE
        else -> StepScope.GLOBAL
    }

    val positionalParams = mergePositionalParams(overloads.flatMap { it.parameters })

    val namedParams = buildNamedParams(overloads, positionalParams)

    val documentation = overloads.firstNotNullOfOrNull { it.documentation }
    val returnType = overloads.firstOrNull()?.returnType

    return ExtractedStep(
        scope = scope,
        positionalParams = positionalParams,
        namedParams = namedParams.toSortedMap(),
        documentation = documentation,
        returnType = returnType,
    )
}

private fun mergePositionalParams(params: List<ParameterDescriptor>): List<String> {
    val seen = LinkedHashSet<String>()
    params.forEach { seen.add(it.name) }
    return seen.toList()
}

private fun buildNamedParams(
    overloads: List<MethodDescriptor>,
    positionalParams: List<String>,
): MutableMap<String, ExtractedParameter> {
    val merged = mutableMapOf<String, ExtractedParameter>()

    overloads.forEach { method ->
        method.parameters.forEach { param ->
            merged[param.name] = mergeExtractedParameter(
                existing = merged[param.name],
                candidate = ExtractedParameter(
                    type = simplifyType(param.type),
                    defaultValue = null,
                ),
            )
        }

        method.namedParameters.forEach { param ->
            merged[param.name] = mergeExtractedParameter(
                existing = merged[param.name],
                candidate = ExtractedParameter(
                    type = simplifyType(param.type),
                    defaultValue = param.defaultValue,
                ),
            )
        }
    }

    positionalParams.forEach { positionalName ->
        merged.putIfAbsent(
            positionalName,
            ExtractedParameter(
                type = OBJECT_TYPE,
                defaultValue = null,
            ),
        )
    }

    return merged
}

private fun mergeExtractedParameter(existing: ExtractedParameter?, candidate: ExtractedParameter): ExtractedParameter {
    if (existing == null) return candidate

    val mergedType = when {
        existing.type == OBJECT_TYPE && candidate.type != OBJECT_TYPE -> candidate.type
        existing.type == SIMPLE_OBJECT_TYPE && candidate.type != SIMPLE_OBJECT_TYPE -> candidate.type
        else -> existing.type
    }

    val mergedDefault = existing.defaultValue ?: candidate.defaultValue

    return ExtractedParameter(
        type = mergedType,
        defaultValue = mergedDefault,
    )
}

private fun determineScope(method: MethodDescriptor): StepScope {
    val scopeType = (method.context["scope"] as? Map<*, *>)?.get("type")?.toString()

    return when (scopeType) {
        "script" -> StepScope.GLOBAL
        "closure" -> determineClosureScope(method.enclosingCall)
        else -> StepScope.GLOBAL
    }
}

private fun determineClosureScope(enclosingCall: String?): StepScope = when (enclosingCall) {
    "stage" -> StepScope.STAGE
    "node" -> StepScope.NODE
    null -> {
        // NOTE: Heuristic - `closureScope()` does not uniquely identify `node {}` vs `stage {}` in
        // Jenkins-generated GDSL.
        // TODO: Use richer context capture (e.g. nested enclosing call stacks) or parse Jenkins'
        // `@RequiresContext` annotations to make this deterministic.
        StepScope.NODE
    }

    else -> {
        // NOTE: Heuristic - unknown enclosing call, defaulting to NODE as it's more common.
        // TODO: Capture more context from the GDSL runtime so this mapping is deterministic.
        StepScope.NODE
    }
}
