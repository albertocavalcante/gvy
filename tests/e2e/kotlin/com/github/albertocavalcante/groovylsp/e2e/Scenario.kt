package com.github.albertocavalcante.groovylsp.e2e

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Custom Jackson deserializer for kotlinx.serialization.json.JsonElement.
 * Bridges YAML/JSON parsing (Jackson) with runtime operations (kotlinx.serialization).
 *
 * TODO: Remove this bridge when kotlinx.serialization adds official YAML support.
 *       Track: https://github.com/Kotlin/kotlinx.serialization/issues/1836
 */
object JsonElementDeserializer : StdDeserializer<JsonElement>(JsonElement::class.java) {
    private fun readResolve(): Any = JsonElementDeserializer

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): JsonElement =
        treeNodeToJsonElement(p.codec.readTree(p))

    private fun treeNodeToJsonElement(node: TreeNode): JsonElement = when (node) {
        is ObjectNode -> JsonObject(
            node.fields().asSequence().associate { (k, v) -> k to treeNodeToJsonElement(v) },
        )

        is ArrayNode -> JsonArray(
            node.elements().asSequence().map { treeNodeToJsonElement(it) }.toList(),
        )

        is ValueNode -> when {
            node.isNull -> JsonNull
            node.isTextual -> JsonPrimitive(node.asText())
            node.isBoolean -> JsonPrimitive(node.asBoolean())
            node.isInt || node.isLong -> JsonPrimitive(node.asLong())
            node.isFloat || node.isDouble || node.isNumber -> JsonPrimitive(node.asDouble())
            else -> JsonPrimitive(node.asText())
        }

        else -> JsonNull
    }
}

/**
 * Jackson-based implementation of [ScenarioParser].
 *
 * Uses Jackson with YAML support for parsing scenario files.
 * The polymorphic [ScenarioStep] types are handled via [JsonTypeInfo.As.WRAPPER_OBJECT].
 *
 * TODO: Replace with kotlinx.serialization implementation when official YAML support is added.
 *       Track: https://github.com/Kotlin/kotlinx.serialization/issues/1836
 */
class JacksonScenarioParser : ScenarioParser {

    private val jsonElementModule = SimpleModule().apply {
        addDeserializer(JsonElement::class.java, JsonElementDeserializer)
    }

    private val objectMapper: ObjectMapper = ObjectMapper(YAMLFactory()).apply {
        registerKotlinModule()
        registerModule(jsonElementModule)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true)
        enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
    }

    override fun parseScenarioDefinition(yaml: String): ScenarioDefinition = try {
        // YAML file structure matches Scenario, so we parse Scenario and wrap it
        val scenario = objectMapper.readValue(yaml, Scenario::class.java)
        ScenarioDefinition(scenario = scenario)
    } catch (e: Exception) {
        throw ScenarioParseException("Failed to parse scenario YAML", e)
    }
}

// =============================================================================
// Data Classes
// =============================================================================
// These are pure Kotlin data classes with Jackson annotations for polymorphism.
// TODO: When migrating to kotlinx.serialization:
//       - Add @Serializable annotations
//       - Remove Jackson annotations
//       - Use sealed class polymorphism with @SerialName
// =============================================================================

data class ScenarioDefinition(val scenario: Scenario, val source: String = "")

data class Scenario(
    val name: String,
    val description: String? = null,
    val server: ServerConfig = ServerConfig(),
    val workspace: WorkspaceConfig? = null,
    val steps: List<ScenarioStep>,
)

data class ServerConfig(
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val mode: ServerLaunchMode = ServerLaunchMode.Stdio,
)

enum class ServerLaunchMode {
    Stdio,
    Socket,
    InProcess,
}

data class WorkspaceConfig(val fixture: String? = null)

/**
 * Represents a single step in a test scenario.
 *
 * Jackson polymorphism uses WRAPPER_OBJECT style to match YAML structure:
 * ```yaml
 * - initialize: { rootUri: "..." }
 * - request: { method: "..." }
 * ```
 *
 * TODO: When migrating to kotlinx.serialization, use sealed interface with @SerialName:
 *       @Serializable sealed interface ScenarioStep {
 *           @Serializable @SerialName("initialize") data class Initialize(...) : ScenarioStep
 *       }
 */
@JsonTypeInfo(use = Id.NAME, include = As.WRAPPER_OBJECT)
@JsonSubTypes(
    Type(value = ScenarioStep.Initialize::class, name = "initialize"),
    Type(value = ScenarioStep.Initialized::class, name = "initialized"),
    Type(value = ScenarioStep.Shutdown::class, name = "shutdown"),
    Type(value = ScenarioStep.Exit::class, name = "exit"),
    Type(value = ScenarioStep.OpenDocument::class, name = "openDocument"),
    Type(value = ScenarioStep.ChangeDocument::class, name = "changeDocument"),
    Type(value = ScenarioStep.SaveDocument::class, name = "saveDocument"),
    Type(value = ScenarioStep.CloseDocument::class, name = "closeDocument"),
    Type(value = ScenarioStep.SendRequest::class, name = "request"),
    Type(value = ScenarioStep.SendNotification::class, name = "notification"),
    Type(value = ScenarioStep.WaitNotification::class, name = "waitNotification"),
    Type(value = ScenarioStep.Assert::class, name = "assert"),
    Type(value = ScenarioStep.Completion::class, name = "completion"),
    Type(value = ScenarioStep.CodeAction::class, name = "codeAction"),
    Type(value = ScenarioStep.Formatting::class, name = "formatting"),
    Type(value = ScenarioStep.Rename::class, name = "rename"),
    Type(value = ScenarioStep.Wait::class, name = "wait"),
    // CLI testing steps
    Type(value = ScenarioStep.DownloadPlugin::class, name = "downloadPlugin"),
    Type(value = ScenarioStep.CliCommand::class, name = "cli"),
    Type(value = ScenarioStep.GoldenAssert::class, name = "goldenAssert"),
)
sealed interface ScenarioStep {
    data class Initialize(val rootUri: String? = null, val initializationOptions: JsonElement? = null) : ScenarioStep

    data object Initialized : ScenarioStep

    data object Shutdown : ScenarioStep

    data object Exit : ScenarioStep

    data class Wait(val duration: Long) : ScenarioStep

    data class OpenDocument(
        val uri: String? = null,
        val path: String? = null,
        val languageId: String = "groovy",
        val version: Int = 1,
        val text: String = "",
    ) : ScenarioStep

    data class ChangeDocument(
        val uri: String? = null,
        val path: String? = null,
        val version: Int = 1,
        val text: String? = null, // Full document replacement (null range in LSP)
        val contentChanges: List<TestTextDocumentContentChangeEvent> = emptyList(),
    ) : ScenarioStep

    data class SaveDocument(val uri: String? = null, val path: String? = null, val text: String? = null) : ScenarioStep

    data class CloseDocument(val uri: String? = null, val path: String? = null) : ScenarioStep

    data class SendRequest(
        val method: String,
        val params: JsonElement? = null,
        val saveAs: String? = null,
        val extract: List<JsonExtraction> = emptyList(),
        val timeoutMs: Long? = null,
    ) : ScenarioStep

    data class SendNotification(val method: String, val params: JsonElement? = null) : ScenarioStep

    data class WaitNotification(
        val method: String,
        val checks: List<JsonCheck> = emptyList(),
        val saveAs: String? = null,
        val optional: Boolean = false,
        val timeoutMs: Long? = null,
    ) : ScenarioStep

    data class Assert(val source: String? = null, val checks: List<JsonCheck> = emptyList()) : ScenarioStep

    // High-level DSL steps (syntactic sugar for common LSP operations)
    data class Completion(
        val uri: String? = null,
        val path: String? = null,
        val line: Int = 0,
        val character: Int = 0,
        val checks: List<JsonCheck> = emptyList(),
    ) : ScenarioStep

    data class CodeAction(
        val uri: String? = null,
        val path: String? = null,
        val range: TestRange? = null,
        val checks: List<JsonCheck> = emptyList(),
    ) : ScenarioStep

    data class Formatting(
        val uri: String? = null,
        val path: String? = null,
        val checks: List<JsonCheck> = emptyList(),
    ) : ScenarioStep

    data class Rename(
        val uri: String? = null,
        val path: String? = null,
        val line: Int = 0,
        val character: Int = 0,
        val newName: String = "",
        val checks: List<JsonCheck> = emptyList(),
    ) : ScenarioStep

    // =========================================================================
    // CLI Testing Steps
    // =========================================================================

    /**
     * Downloads a Jenkins plugin from the Jenkins releases repository.
     * Plugins are cached in tests/e2e/resources/plugins/ for reproducibility.
     */
    data class DownloadPlugin(
        val id: String,
        val version: String,
        val source: PluginSource = PluginSource.JENKINS_RELEASES,
        val saveAs: String? = null, // Variable name to store downloaded path
    ) : ScenarioStep

    /**
     * Executes a GLS CLI command and captures the result.
     */
    data class CliCommand(
        val command: String, // e.g., "jenkins extract"
        val args: List<String> = emptyList(),
        val saveAs: String? = null, // Variable name to store result
        val checks: List<JsonCheck> = emptyList(), // Assertions on output
        val timeoutSeconds: Long = 60,
        val expectExitCode: Int = 0,
    ) : ScenarioStep

    /**
     * Compares an output file against a golden (expected) file.
     * If updateGolden system property is set, updates the golden file instead.
     */
    data class GoldenAssert(
        val actual: String, // Path to actual output file (supports {{variables}})
        val expected: String, // Path to golden file (relative to resources/golden/)
        val mode: GoldenMode = GoldenMode.JSON,
        val message: String? = null,
    ) : ScenarioStep
}

// =============================================================================
// Supporting Types
// =============================================================================

/**
 * Test-specific representation of LSP TextDocumentContentChangeEvent.
 * Named with 'Test' prefix to avoid confusion with org.eclipse.lsp4j.TextDocumentContentChangeEvent.
 */
data class TestTextDocumentContentChangeEvent(
    val range: TestRange? = null,
    val rangeLength: Int? = null,
    val text: String = "",
)

data class TestRange(val start: TestPosition, val end: TestPosition)

data class TestPosition(val line: Int, val character: Int)

data class JsonExtraction(val variable: String, val jsonPath: String)

data class JsonCheck(val jsonPath: String, val expect: JsonExpectation, val message: String? = null)

data class JsonExpectation(
    val type: ExpectationType = ExpectationType.EXISTS,
    val value: JsonElement? = null,
    val values: List<JsonElement> = emptyList(),
)

enum class ExpectationType {
    EXISTS,
    NOT_EXISTS,
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    CONTAINS_ANY,
    CONTAINS_ALL,
    MATCHES_REGEX,
    SIZE,
    EMPTY,
    NOT_EMPTY,
    GTE, // Greater than or equal
    LTE, // Less than or equal
    ANY_CONTAINS, // For arrays: any element contains the substring
    NONE_CONTAINS, // For arrays: no element contains the substring
}

// =============================================================================
// CLI Testing Supporting Types
// =============================================================================

enum class PluginSource {
    JENKINS_RELEASES, // https://repo.jenkins-ci.org/releases/
    MAVEN_CENTRAL, // For plugins published to Maven Central
    LOCAL, // Local file path
}

enum class GoldenMode {
    JSON, // Structural JSON comparison (ignores formatting)
    TEXT, // Exact text comparison
    BINARY, // Binary comparison
}
