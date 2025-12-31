package com.github.albertocavalcante.groovylsp.e2e

import com.github.albertocavalcante.groovylsp.e2e.JsonBridge.toJavaObject
import com.github.albertocavalcante.groovylsp.e2e.JsonBridge.wrapJavaObject
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.InitializeResult

data class ScenarioContext(
    val definition: ScenarioDefinition,
    val session: LanguageServerSession,
    val workspace: ScenarioWorkspace,
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    },
    val jsonPathConfig: Configuration,
) {
    val state: ScenarioState = ScenarioState()
    val variables: MutableMap<String, JsonElement> = mutableMapOf()
    val savedResults: MutableMap<String, JsonElement> = mutableMapOf()
    var lastResult: JsonElement? = null
    var currentStepIndex: Int = 0
    var totalSteps: Int = 0

    fun registerBuiltInVariables() {
        val workspaceObject = buildJsonObject {
            workspace.rootUri?.let { put("uri", it) }
            put("path", workspace.rootDir.toString())
        }
        variables["workspace"] = workspaceObject
        workspaceObject["uri"]?.let { variables["workspace.uri"] = it }
        variables["workspace.path"] = workspaceObject["path"] ?: JsonNull
        variables["temp"] = variables["workspace.path"] ?: JsonNull
    }

    fun resolveUri(uri: String?, path: String?): String = when {
        uri != null -> interpolateString(uri)
        path != null -> workspace.resolveUri(interpolateString(path))
        else -> error("Document reference must specify either uri or path")
    }

    fun saveResult(name: String, node: JsonElement) {
        savedResults[name] = node
        variables[name] = node
    }

    fun setVariable(name: String, node: JsonElement) {
        variables[name] = node
    }

    fun interpolateString(value: String): String {
        var result = value
        PLACEHOLDER_REGEX.findAll(value).forEach { match ->
            val expression = match.groupValues[1].trim()
            val replacement = resolveExpression(expression)
            result = result.replace(match.value, replacement)
        }
        return result
    }

    fun interpolateNode(node: JsonElement?): JsonElement? {
        if (node == null) return null
        return when (node) {
            is JsonPrimitive -> {
                if (node.isString) {
                    JsonPrimitive(interpolateString(node.content))
                } else {
                    node
                }
            }

            is JsonArray -> JsonArray(node.map { interpolateNode(it) ?: JsonNull })
            is JsonObject -> JsonObject(node.mapValues { (_, value) -> interpolateNode(value) ?: JsonNull })
            is JsonNull -> JsonNull
        }
    }

    private fun resolveExpression(expr: String): String {
        val (variableName, jsonPath) = if (expr.contains(".")) {
            val firstDot = expr.indexOf('.')
            expr.substring(0, firstDot) to expr.substring(firstDot + 1)
        } else {
            expr to null
        }

        val variableNode = variables[variableName]
            ?: error("Unknown variable '$variableName' in expression '{{$expr}}'")

        val targetNode = if (jsonPath != null) {
            val javaObject = variableNode.toJavaObject()
            val document = JsonPath.using(jsonPathConfig).parse(javaObject)
            val value = try {
                document.read<Any?>("$." + jsonPath)
            } catch (ex: PathNotFoundException) {
                throw IllegalArgumentException("Path '.$jsonPath' not found in variable '$variableName'", ex)
            }
            wrapJavaObject(value)
        } else {
            variableNode
        }

        return when (targetNode) {
            is JsonPrimitive -> targetNode.content // content handles strings, numbers, booleans effectively
            is JsonNull -> "null"
            else -> json.encodeToString(JsonElement.serializer(), targetNode)
        }
    }

    fun evaluateCheck(node: JsonElement, check: JsonCheck, quiet: Boolean = false): Boolean {
        val javaObject = node.toJavaObject()
        val (pathExists, readValue) = javaObject?.let {
            val document = JsonPath.using(jsonPathConfig).parse(it)
            try {
                true to document.read<Any?>(check.jsonPath)
            } catch (ex: PathNotFoundException) {
                false to null
            }
        } ?: (false to null)

        val expectation = check.expect.interpolate(this)
        val messagePrefix = buildString {
            append("Assertion failed for jsonPath '${check.jsonPath}' with expectation ${expectation.type}")
            check.message?.let {
                append(" (")
                append(it)
                append(")")
            }
        }

        val actualNode = wrapJavaObject(readValue)
        val success = JsonExpectationEvaluator.matches(
            expectation = expectation,
            pathExists = pathExists,
            actualNode = actualNode,
        )

        if (!success) {
            if (!quiet) {
                val actualDescription = if (!pathExists) {
                    "path not found"
                } else {
                    json.encodeToString(JsonElement.serializer(), actualNode)
                }
                throw AssertionError("$messagePrefix. Actual: $actualDescription")
            }
            return false
        }

        return true
    }

    fun normalizeResponse(method: String, response: JsonElement): JsonElement {
        if (response !is JsonObject) {
            return response
        }

        // Normalize Either-style responses into a consistent shape
        val leftNode = response["left"]
        val rightNode = response["right"]
        val hasLeft = leftNode != null && leftNode !is JsonNull
        val hasRight = rightNode != null && rightNode !is JsonNull

        val size = response.size

        if (hasLeft && !hasRight && size == (if (response.containsKey("right")) 2 else 1)) {
            return if (leftNode is JsonArray) {
                buildJsonObject {
                    put("dataOrigin", "left")
                    put("items", leftNode)
                }
            } else {
                leftNode ?: JsonNull
            }
        }

        if (hasRight && !hasLeft && size == (if (response.containsKey("left")) 2 else 1)) {
            return if (rightNode is JsonArray) {
                buildJsonObject {
                    put("dataOrigin", "right")
                    put("items", rightNode)
                }
            } else {
                rightNode ?: JsonNull
            }
        }

        return response
    }

    companion object {
        private val PLACEHOLDER_REGEX = "\\{\\{([^}]+)}}".toRegex()
    }

    private fun JsonExpectation.interpolate(context: ScenarioContext): JsonExpectation = JsonExpectation(
        type = type,
        value = context.interpolateNode(value),
        values = values.map { context.interpolateNode(it) ?: JsonNull },
    )
}

data class ScenarioState(var initializedResult: InitializeResult? = null)

class ScenarioWorkspace(val rootDir: java.nio.file.Path) {
    val rootUri: String? = rootDir.toUri().toString()

    fun resolveUri(relative: String): String = rootDir.resolve(relative).normalize().toUri().toString()

    fun cleanup() {
        WorkspaceFixture.cleanup(rootDir)
    }
}

object JsonExpectationEvaluator {
    // Cache compiled regex patterns for performance
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun matches(expectation: JsonExpectation, pathExists: Boolean, actualNode: JsonElement?): Boolean =
        when (expectation.type) {
            ExpectationType.EXISTS -> pathExists
            ExpectationType.NOT_EXISTS -> !pathExists
            ExpectationType.EQUALS -> pathExists && equals(actualNode, expectation.value)
            ExpectationType.NOT_EQUALS -> !pathExists || !equals(actualNode, expectation.value)
            ExpectationType.CONTAINS -> pathExists && contains(actualNode, expectation.value)
            ExpectationType.NOT_CONTAINS -> !pathExists || !contains(actualNode, expectation.value)
            ExpectationType.CONTAINS_ANY -> pathExists && containsAny(actualNode, expectation.values)
            ExpectationType.CONTAINS_ALL -> pathExists && containsAll(actualNode, expectation.values)
            ExpectationType.MATCHES_REGEX -> pathExists && matchesRegex(actualNode, expectation.value)
            ExpectationType.SIZE -> pathExists && hasSize(actualNode, expectation.value)
            ExpectationType.EMPTY -> !pathExists || isEmpty(actualNode)
            ExpectationType.NOT_EMPTY -> pathExists && !isEmpty(actualNode)
            ExpectationType.GTE -> pathExists && isGreaterThanOrEqual(actualNode, expectation.value)
            ExpectationType.LTE -> pathExists && isLessThanOrEqual(actualNode, expectation.value)
            ExpectationType.ANY_CONTAINS -> pathExists && anyContainsSubstring(actualNode, expectation.value)
            ExpectationType.NONE_CONTAINS -> !pathExists || !anyContainsSubstring(actualNode, expectation.value)
        }

    private fun equals(actual: JsonElement?, expected: JsonElement?): Boolean = actual == expected

    private fun contains(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (actual == null || expected == null) return false
        return when (actual) {
            is JsonPrimitive -> actual.isString && actual.content.contains(expected.jsonPrimitive.content)
            is JsonArray -> actual.any { element -> structurallyContains(element, expected) }
            else -> false
        }
    }

    /**
     * Checks if [actual] structurally contains [expected] as a subset.
     * All fields specified in [expected] must exist in [actual] with matching values.
     * Extra fields in [actual] are allowed (not compared).
     */
    private fun structurallyContains(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (expected == null || expected is JsonNull) return true
        if (actual == null || actual is JsonNull) return false

        return when {
            expected is JsonPrimitive && actual is JsonPrimitive -> actual == expected
            expected is JsonObject && actual is JsonObject -> {
                expected.entries.all { (key, value) ->
                    actual.containsKey(key) && structurallyContains(actual[key], value)
                }
            }

            expected is JsonArray && actual is JsonArray -> {
                // All elements in expected must be structurally contained in some element of actual
                expected.all { exp -> actual.any { act -> structurallyContains(act, exp) } }
            }

            else -> false
        }
    }

    private fun containsAny(actual: JsonElement?, expected: List<JsonElement>): Boolean =
        expected.any { contains(actual, it) }

    private fun containsAll(actual: JsonElement?, expected: List<JsonElement>): Boolean =
        expected.all { contains(actual, it) }

    private fun matchesRegex(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (actual == null || expected == null) return false
        val pattern = expected.jsonPrimitive.content
        return try {
            val regex = regexCache.getOrPut(pattern) { Regex(pattern) }
            regex.containsMatchIn(actual.jsonPrimitive.content)
        } catch (ex: Exception) {
            false
        }
    }

    private fun hasSize(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (actual == null || expected == null || expected !is JsonPrimitive) return false
        val size = expected.intOrNull ?: return false
        return when (actual) {
            is JsonArray -> actual.size == size
            is JsonPrimitive -> if (actual.isString) actual.content.length == size else false
            is JsonObject -> actual.size == size
            else -> false
        }
    }

    private fun isEmpty(actual: JsonElement?): Boolean = actual == null || actual is JsonNull ||
        (actual is JsonPrimitive && actual.isString && actual.content.isEmpty()) ||
        (actual is JsonArray && actual.isEmpty()) ||
        (actual is JsonObject && actual.isEmpty())

    private fun isGreaterThanOrEqual(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (actual == null || expected == null) return false
        val actualNum = (actual as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return false
        val expectedNum = (expected as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return false
        return actualNum >= expectedNum
    }

    private fun isLessThanOrEqual(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (actual == null || expected == null) return false
        val actualNum = (actual as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return false
        val expectedNum = (expected as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return false
        return actualNum <= expectedNum
    }

    /**
     * Checks if any element in an array contains the expected substring.
     * For string primitives, does substring matching.
     * For arrays, checks if any string element contains the substring.
     */
    private fun anyContainsSubstring(actual: JsonElement?, expected: JsonElement?): Boolean {
        if (actual == null || expected == null) return false
        val substring = (expected as? JsonPrimitive)?.content ?: return false

        return when (actual) {
            is JsonPrimitive -> actual.isString && actual.content.contains(substring)
            is JsonArray -> actual.any { element ->
                element is JsonPrimitive && element.isString && element.content.contains(substring)
            }

            else -> false
        }
    }
}
