package com.github.albertocavalcante.groovylsp.e2e

import com.fasterxml.jackson.databind.JsonNode

data class Scenario(
    val name: String,
    val description: String? = null,
    val server: ServerConfig = ServerConfig(),
    val workspace: WorkspaceConfig? = null,
    val steps: List<ScenarioStep>,
)

data class ServerConfig(val mode: ServerLaunchMode = ServerLaunchMode.Stdio)

data class WorkspaceConfig(val fixture: String? = null) {
    init {
        require(fixture == null || fixture.isNotBlank()) {
            "workspace.fixture must be blank or a relative directory name"
        }
    }
}

sealed interface ScenarioStep {
    data class Initialize(val rootUri: String? = null, val initializationOptions: JsonNode? = null) : ScenarioStep

    data object Initialized : ScenarioStep

    data object Shutdown : ScenarioStep

    data object Exit : ScenarioStep

    data class OpenDocument(
        val uri: String? = null,
        val path: String? = null,
        val languageId: String,
        val version: Int,
        val text: String,
    ) : ScenarioStep

    data class ChangeDocument(
        val uri: String? = null,
        val path: String? = null,
        val version: Int,
        val contentChanges: List<TextDocumentContentChange>,
    ) : ScenarioStep

    data class SaveDocument(val uri: String? = null, val path: String? = null, val text: String? = null) : ScenarioStep

    data class CloseDocument(val uri: String? = null, val path: String? = null) : ScenarioStep

    data class SendRequest(
        val method: String,
        val params: JsonNode? = null,
        val saveAs: String? = null,
        val extract: List<JsonExtraction> = emptyList(),
        val timeoutMs: Long? = null,
    ) : ScenarioStep

    data class SendNotification(val method: String, val params: JsonNode? = null) : ScenarioStep

    data class WaitNotification(
        val method: String,
        val checks: List<JsonCheck> = emptyList(),
        val saveAs: String? = null,
        val timeoutMs: Long? = null,
    ) : ScenarioStep

    data class Assert(val source: String? = null, val checks: List<JsonCheck>) : ScenarioStep
}

data class TextDocumentContentChange(val text: String, val range: RangeSpec? = null, val rangeLength: Int? = null)

data class RangeSpec(val start: PositionSpec, val end: PositionSpec)

data class PositionSpec(val line: Int, val character: Int)

data class JsonExtraction(val variable: String, val jsonPath: String)

data class JsonCheck(val jsonPath: String, val expect: JsonExpectation, val message: String? = null)

data class JsonExpectation(
    val type: ExpectationType,
    val value: JsonNode? = null,
    val values: List<JsonNode> = emptyList(),
)

enum class ExpectationType {
    EXISTS,
    NOT_EXISTS,
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    CONTAINS_ANY,
    CONTAINS_ALL,
    MATCHES_REGEX,
    SIZE,
    EMPTY,
    NOT_EMPTY,
}

enum class ServerLaunchMode {
    Stdio,
    Socket,
}
