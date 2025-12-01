package com.github.albertocavalcante.groovylsp.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class ScenarioLoader(private val mapper: ObjectMapper) {
    fun loadAll(directory: Path): List<ScenarioDefinition> {
        require(Files.exists(directory)) {
            "Scenario directory does not exist: $directory"
        }

        Files.walk(directory, 1)
            .use { stream ->
                return stream
                    .filter { path -> path.isRegularFile() && path.extension in setOf("yml", "yaml") }
                    .sorted()
                    .map { path -> load(path) }
                    .collect(Collectors.toList())
            }
    }

    fun load(path: Path): ScenarioDefinition {
        try {
            Files.newBufferedReader(path).use { reader ->
                val root = mapper.readTree(reader)
                return ScenarioDefinition(
                    scenario = parseScenario(root, path),
                    source = path,
                )
            }
        } catch (ex: IOException) {
            throw IllegalStateException("Failed to load scenario file: $path", ex)
        }
    }

    private fun parseScenario(root: JsonNode, path: Path): Scenario {
        val name = root.required("name", path).asText()
        val description = root["description"]?.asText()
        val serverConfig = parseServerConfig(root["server"], path)
        val workspaceConfig = parseWorkspaceConfig(root["workspace"], path)

        val stepsNode = root.required("steps", path)
        require(stepsNode.isArray && stepsNode.size() > 0) {
            "Scenario $name defined in $path must declare at least one step"
        }

        val indexCounter = AtomicInteger(0)
        val steps = stepsNode.map { node ->
            val index = indexCounter.getAndIncrement()
            parseStep(node, path, index)
        }

        return Scenario(
            name = name,
            description = description,
            server = serverConfig,
            workspace = workspaceConfig,
            steps = steps,
        )
    }

    private fun parseServerConfig(node: JsonNode?, path: Path): ServerConfig {
        if (node == null || node.isNull) {
            return ServerConfig()
        }

        require(node.isObject) {
            "server section in $path must be an object"
        }

        val mode = node["mode"]?.asText()?.let { rawMode ->
            when (rawMode.lowercase()) {
                "stdio" -> ServerLaunchMode.Stdio
                "socket" -> ServerLaunchMode.Socket
                else -> error("Unknown server mode '$rawMode' in $path")
            }
        } ?: ServerLaunchMode.Stdio

        return ServerConfig(mode = mode)
    }

    private fun parseWorkspaceConfig(node: JsonNode?, path: Path): WorkspaceConfig? {
        if (node == null || node.isNull) {
            return null
        }

        require(node.isObject) {
            "workspace section in $path must be an object"
        }

        val fixture = node["fixture"]?.asText()

        return WorkspaceConfig(fixture = fixture)
    }

    private fun parseStep(node: JsonNode, path: Path, index: Int): ScenarioStep {
        require(node.isObject) {
            "steps[$index] in $path must be an object with a single key"
        }

        val fields = node.fields()
        require(fields.hasNext()) {
            "steps[$index] in $path must contain a single entry representing the step type"
        }

        val entry = fields.next()
        require(!fields.hasNext()) {
            "steps[$index] in $path contains multiple step definitions, expected only one"
        }

        val stepKey = entry.key
        val valueNode = entry.value
        val context = "steps[$index].$stepKey"

        return when (stepKey.lowercase()) {
            "initialize" -> parseInitializeStep(valueNode, path, context)
            "initialized" -> ScenarioStep.Initialized
            "shutdown" -> ScenarioStep.Shutdown
            "exit" -> ScenarioStep.Exit
            "opendocument" -> parseOpenDocumentStep(valueNode, path, context)
            "changedocument" -> parseChangeDocumentStep(valueNode, path, context)
            "savedocument" -> parseSaveDocumentStep(valueNode, path, context)
            "closedocument" -> parseCloseDocumentStep(valueNode, path, context)
            "request" -> parseRequestStep(valueNode, path, context)
            "notification" -> parseNotificationStep(valueNode, path, context)
            "waitnotification" -> parseWaitNotificationStep(valueNode, path, context)
            "assert" -> parseAssertStep(valueNode, path, context)
            else -> error("Unknown step type '$stepKey' in $path at index $index")
        }
    }

    private fun parseInitializeStep(node: JsonNode?, path: Path, context: String): ScenarioStep.Initialize {
        if (node == null || node.isNull) {
            return ScenarioStep.Initialize()
        }

        require(node.isObject) {
            "$context in $path must be an object"
        }

        val rootUri = node["rootUri"]?.asText()
        val initializationOptions = node["initializationOptions"]?.deepCopy<JsonNode>()

        return ScenarioStep.Initialize(
            rootUri = rootUri,
            initializationOptions = initializationOptions,
        )
    }

    private fun parseOpenDocumentStep(node: JsonNode, path: Path, context: String): ScenarioStep.OpenDocument {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val uri = node["uri"]?.asText()
        val filePath = node["path"]?.asText()
        require(!uri.isNullOrBlank() || !filePath.isNullOrBlank()) {
            "$context in $path must define either 'uri' or 'path'"
        }

        val languageId = node.requireText("languageId", path, context)
        val version = node.requireInt("version", path, context)
        val text = node.requireText("text", path, context)

        return ScenarioStep.OpenDocument(
            uri = uri,
            path = filePath,
            languageId = languageId,
            version = version,
            text = text,
        )
    }

    private fun parseChangeDocumentStep(node: JsonNode, path: Path, context: String): ScenarioStep.ChangeDocument {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val uri = node["uri"]?.asText()
        val filePath = node["path"]?.asText()
        require(!uri.isNullOrBlank() || !filePath.isNullOrBlank()) {
            "$context in $path must define either 'uri' or 'path'"
        }

        val version = node.requireInt("version", path, context)
        val changes = mutableListOf<TextDocumentContentChange>()

        if (node.has("contentChanges")) {
            val changesNode = node["contentChanges"]
            require(changesNode.isArray && changesNode.size() > 0) {
                "$context.contentChanges in $path must be a non-empty array"
            }
            changesNode.forEachIndexed { changeIndex, changeNode ->
                changes.add(parseContentChange(changeNode, path, "$context.contentChanges[$changeIndex]"))
            }
        } else if (node.has("text")) {
            val text = node.requireText("text", path, context)
            changes.add(TextDocumentContentChange(text = text))
        } else {
            error("$context in $path must define either 'text' or 'contentChanges'")
        }

        return ScenarioStep.ChangeDocument(
            uri = uri,
            path = filePath,
            version = version,
            contentChanges = changes,
        )
    }

    private fun parseContentChange(node: JsonNode, path: Path, context: String): TextDocumentContentChange {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val text = node.requireText("text", path, context)
        val range = node["range"]?.let { parseRange(it, path, "$context.range") }
        val rangeLength = node["rangeLength"]?.takeIf { it.isInt }?.asInt()

        return TextDocumentContentChange(
            text = text,
            range = range,
            rangeLength = rangeLength,
        )
    }

    private fun parseRange(node: JsonNode, path: Path, context: String): RangeSpec {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val start = node.required("start", path, context)
        val end = node.required("end", path, context)
        return RangeSpec(
            start = parsePosition(start, path, "$context.start"),
            end = parsePosition(end, path, "$context.end"),
        )
    }

    private fun parsePosition(node: JsonNode, path: Path, context: String): PositionSpec {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val line = node.requireInt("line", path, context)
        val character = node.requireInt("character", path, context)

        return PositionSpec(line = line, character = character)
    }

    private fun parseSaveDocumentStep(node: JsonNode, path: Path, context: String): ScenarioStep.SaveDocument {
        require(node.isObject) {
            "$context in $path must be an object"
        }
        val uri = node["uri"]?.asText()
        val filePath = node["path"]?.asText()
        require(!uri.isNullOrBlank() || !filePath.isNullOrBlank()) {
            "$context in $path must define either 'uri' or 'path'"
        }

        val text = node["text"]?.asText()

        return ScenarioStep.SaveDocument(
            uri = uri,
            path = filePath,
            text = text,
        )
    }

    private fun parseCloseDocumentStep(node: JsonNode, path: Path, context: String): ScenarioStep.CloseDocument {
        require(node.isObject) {
            "$context in $path must be an object"
        }
        val uri = node["uri"]?.asText()
        val filePath = node["path"]?.asText()
        require(!uri.isNullOrBlank() || !filePath.isNullOrBlank()) {
            "$context in $path must define either 'uri' or 'path'"
        }

        return ScenarioStep.CloseDocument(
            uri = uri,
            path = filePath,
        )
    }

    private fun parseRequestStep(node: JsonNode, path: Path, context: String): ScenarioStep.SendRequest {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val method = node.requireText("method", path, context)
        val saveAs = node["saveAs"]?.asText()
        val timeoutMs = node["timeoutMs"]?.takeIf { it.isNumber }?.asLong()
        val params = node["params"]?.deepCopy<JsonNode>()
        val extractSpecs = node["extract"]?.let { extractNode ->
            require(extractNode.isArray) {
                "$context.extract in $path must be an array"
            }
            extractNode.mapIndexed { extractIndex, extract ->
                require(extract.isObject) {
                    "$context.extract[$extractIndex] in $path must be an object"
                }
                val variable = extract.requireText("variable", path, "$context.extract[$extractIndex]")
                val jsonPath = extract.requireText("jsonPath", path, "$context.extract[$extractIndex]")
                JsonExtraction(variable = variable, jsonPath = jsonPath)
            }
        } ?: emptyList()

        return ScenarioStep.SendRequest(
            method = method,
            params = params,
            saveAs = saveAs,
            extract = extractSpecs,
            timeoutMs = timeoutMs,
        )
    }

    private fun parseNotificationStep(node: JsonNode, path: Path, context: String): ScenarioStep.SendNotification {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val method = node.requireText("method", path, context)
        val params = node["params"]?.deepCopy<JsonNode>()

        return ScenarioStep.SendNotification(
            method = method,
            params = params,
        )
    }

    private fun parseWaitNotificationStep(node: JsonNode, path: Path, context: String): ScenarioStep.WaitNotification {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val method = node.requireText("method", path, context)
        val saveAs = node["saveAs"]?.asText()
        val timeoutMs = node["timeoutMs"]?.takeIf { it.isNumber }?.asLong()
        val checks = node["checks"]?.let { checksNode ->
            require(checksNode.isArray && checksNode.size() > 0) {
                "$context.checks in $path must be a non-empty array"
            }
            checksNode.mapIndexed { checkIndex, checkNode ->
                parseJsonCheck(checkNode, path, "$context.checks[$checkIndex]")
            }
        } ?: emptyList()

        val optional = node["optional"]?.asBoolean(false) ?: false

        return ScenarioStep.WaitNotification(
            method = method,
            checks = checks,
            saveAs = saveAs,
            timeoutMs = timeoutMs,
            optional = optional,
        )
    }

    private fun parseAssertStep(node: JsonNode, path: Path, context: String): ScenarioStep.Assert {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val source = node["source"]?.asText()
        val checksNode = node.required("checks", path, context)
        require(checksNode.isArray && checksNode.size() > 0) {
            "$context.checks in $path must be a non-empty array"
        }

        val checks = checksNode.mapIndexed { index, checkNode ->
            parseJsonCheck(checkNode, path, "$context.checks[$index]")
        }

        return ScenarioStep.Assert(
            source = source,
            checks = checks,
        )
    }

    private fun parseJsonCheck(node: JsonNode, path: Path, context: String): JsonCheck {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val jsonPath = node.requireText("jsonPath", path, context)
        val expectNode = node.required("expect", path, context)
        val expectation = parseExpectation(expectNode, path, "$context.expect")
        val message = node["message"]?.asText()

        return JsonCheck(
            jsonPath = jsonPath,
            expect = expectation,
            message = message,
        )
    }

    private fun parseExpectation(node: JsonNode, path: Path, context: String): JsonExpectation {
        require(node.isObject) {
            "$context in $path must be an object"
        }

        val rawType = node.requireText("type", path, context)
        val normalizedType = rawType.uppercase().replace('-', '_')
        val type = ExpectationType.values().firstOrNull { it.name == normalizedType }
            ?: error("$context.type in $path contains unknown expectation type '$rawType'")

        val value = node["value"]?.deepCopy<JsonNode>()
        val values = node["values"]?.let { valuesNode ->
            require(valuesNode.isArray) {
                "$context.values in $path must be an array"
            }
            valuesNode.map { it.deepCopy<JsonNode>() }
        } ?: emptyList()

        return JsonExpectation(
            type = type,
            value = value,
            values = values,
        )
    }

    private fun JsonNode.required(field: String, path: Path, context: String = ""): JsonNode =
        this[field] ?: error("Missing required field '$field' in $context of scenario file $path")

    private fun JsonNode.requireText(field: String, path: Path, context: String): String {
        val node = required(field, path, context)
        require(node.isTextual) {
            "Expected '$field' in $context of $path to be a string"
        }
        return node.asText()
    }

    private fun JsonNode.requireInt(field: String, path: Path, context: String): Int {
        val node = required(field, path, context)
        require(node.isInt) {
            "Expected '$field' in $context of $path to be an integer"
        }
        return node.asInt()
    }
}

data class ScenarioDefinition(val scenario: Scenario, val source: Path)
