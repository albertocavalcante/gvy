package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.e2e.JsonBridge.toJavaObject
import com.github.albertocavalcante.gvy.gls.e2e.JsonBridge.wrapJavaObject
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

    /** Cursor markers seen so far, keyed by name; the anonymous `|^|` marker uses the empty name. */
    val cursors: MutableMap<String, CursorLocation> = mutableMapOf()
    var lastResult: JsonElement? = null
    var currentStepIndex: Int = 0
    var totalSteps: Int = 0

    fun registerBuiltInVariables() {
        val workspaceObject = buildJsonObject {
            put("uri", workspace.rootUri)
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

    /** Records where [markers] point in the document at [uri], replacing earlier cursors with the same name. */
    fun registerCursors(uri: String, markers: List<CursorMarker>) {
        val path = workspaceRelativePath(uri)
        markers.forEach { marker ->
            cursors[marker.name.orEmpty()] = CursorLocation(uri, path, marker.line, marker.character)
        }
    }

    /**
     * Strips cursor markers from [text], the new full text of the document at [uri], and returns the text to send.
     * Cursors from the document's previous text are dropped first, so a name the new text no longer marks
     * cannot resolve to a stale position.
     */
    fun stripCursorMarkers(uri: String, text: String): String {
        val marked = try {
            CursorMarkers.strip(text)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("Document '$uri': ${ex.message}", ex)
        }
        cursors.values.removeAll { it.uri == uri }
        registerCursors(uri, marked.markers)
        return marked.text
    }

    /** The decoded, `/`-separated path of [uri] relative to the workspace root, or null when it lies outside. */
    private fun workspaceRelativePath(uri: String): String? = runCatching {
        val file = java.nio.file.Path.of(java.net.URI.create(uri)).normalize()
        val root = workspace.rootDir.toAbsolutePath().normalize()
        if (file.startsWith(root)) root.relativize(file).joinToString("/") else null
    }.getOrNull()

    fun saveResult(name: String, node: JsonElement) {
        requireAssignable(name)
        savedResults[name] = node
        variables[name] = node
    }

    fun setVariable(name: String, node: JsonElement) {
        requireAssignable(name)
        variables[name] = node
    }

    private fun requireAssignable(name: String) {
        require(name != CURSOR_ROOT && !name.startsWith("$CURSOR_ROOT.")) {
            "Variable name '$name' is reserved for cursor markers ({{cursor}}, {{cursor.name}})"
        }
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
                    // A string that is exactly one placeholder takes the value's JSON type, so
                    // `position: "{{cursor.dot}}"` becomes an object and `"{{cursor.dot.line}}"` a number.
                    PLACEHOLDER_REGEX.matchEntire(node.content)
                        ?.let { resolveNode(it.groupValues[1].trim()) }
                        ?: JsonPrimitive(interpolateString(node.content))
                } else {
                    node
                }
            }

            is JsonArray -> JsonArray(node.map { interpolateNode(it) ?: JsonNull })
            is JsonObject -> JsonObject(node.mapValues { (_, value) -> interpolateNode(value) ?: JsonNull })
            is JsonNull -> JsonNull
        }
    }

    private fun resolveExpression(expr: String): String = when (val targetNode = resolveNode(expr)) {
        is JsonPrimitive -> targetNode.content // content handles strings, numbers, booleans effectively
        is JsonNull -> "null"
        else -> json.encodeToString(JsonElement.serializer(), targetNode)
    }

    private fun resolveNode(expr: String): JsonElement {
        if (expr == CURSOR_ROOT || expr.startsWith("$CURSOR_ROOT.")) {
            return resolveCursor(expr)
        }
        val (variableName, jsonPath) = if (expr.contains(".")) {
            val firstDot = expr.indexOf('.')
            expr.substring(0, firstDot) to expr.substring(firstDot + 1)
        } else {
            expr to null
        }

        val variableNode = variables[variableName]
            ?: error("Unknown variable '$variableName' in expression '{{$expr}}'")

        return if (jsonPath != null) {
            val javaObject = variableNode.toJavaObject()
            val document = JsonPath.using(jsonPathConfig).parse(javaObject)
            val value = try {
                document.read<Any?>("$.$jsonPath")
            } catch (ex: PathNotFoundException) {
                throw IllegalArgumentException("Path '.$jsonPath' not found in variable '$variableName'", ex)
            }
            wrapJavaObject(value)
        } else {
            variableNode
        }
    }

    /**
     * Resolves `cursor`, `cursor.<field>`, `cursor.<name>` and `cursor.<name>.<field>`, where a field is one of
     * [CursorMarkers.RESERVED_NAMES]. Without a field the value is an LSP position `{line, character}`.
     */
    private fun resolveCursor(expr: String): JsonElement {
        val segments = expr.split('.').drop(1)
        val (name, field) = when {
            segments.isEmpty() -> "" to null
            segments.size == 1 && segments[0] in CursorMarkers.RESERVED_NAMES -> "" to segments[0]
            segments.size == 1 -> segments[0] to null
            segments.size == 2 -> segments[0] to segments[1]
            else -> throw IllegalArgumentException("Cannot resolve '{{$expr}}': expected cursor[.name][.field]")
        }
        require(field == null || field in CursorMarkers.RESERVED_NAMES) {
            "Unknown cursor field '$field' in '{{$expr}}'; fields are ${CursorMarkers.RESERVED_NAMES}"
        }
        val location = cursors[name] ?: throw IllegalArgumentException(
            if (name.isEmpty()) {
                "'{{$expr}}' needs an anonymous cursor marker |^| in an earlier step or in workspace.files"
            } else {
                "Unknown cursor '$name' in '{{$expr}}'; registered cursors: " +
                    cursors.keys.map { if (it.isEmpty()) "|^|" else it }.sorted()
            },
        )
        return location.field(field, expr)
    }

    private fun CursorLocation.field(field: String?, expr: String): JsonElement = when (field) {
        null -> buildJsonObject {
            put("line", line)
            put("character", character)
        }
        "line" -> JsonPrimitive(line)
        "character" -> JsonPrimitive(character)
        "uri" -> JsonPrimitive(uri)
        else -> JsonPrimitive(
            requireNotNull(path) { "Cursor '{{$expr}}' points at '$uri', which is outside the workspace" },
        )
    }

    fun evaluateCheck(node: JsonElement, check: JsonCheck, quiet: Boolean = false): Boolean {
        val javaObject = node.toJavaObject()
        // Scenarios write variables such as {{workspace.uri}} inside filters, e.g. `$[?(@.uri == '…')]`.
        val jsonPath = interpolateString(check.jsonPath)
        val (pathExists, readValue) = javaObject?.let {
            val document = JsonPath.using(jsonPathConfig).parse(it)
            try {
                true to document.read<Any?>(jsonPath)
            } catch (ex: PathNotFoundException) {
                false to null
            }
        } ?: (false to null)

        val expectation = check.expect.interpolate(this)
        val messagePrefix = buildString {
            append("Assertion failed for jsonPath '$jsonPath' with expectation ${expectation.type}")
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
                leftNode
            }
        }

        if (hasRight && !hasLeft && size == (if (response.containsKey("left")) 2 else 1)) {
            return if (rightNode is JsonArray) {
                buildJsonObject {
                    put("dataOrigin", "right")
                    put("items", rightNode)
                }
            } else {
                rightNode
            }
        }

        return response
    }

    companion object {
        private val PLACEHOLDER_REGEX = "\\{\\{([^}]+)}}".toRegex()
        private const val CURSOR_ROOT = "cursor"
    }

    private fun JsonExpectation.interpolate(context: ScenarioContext): JsonExpectation = copy(
        value = context.interpolateNode(this.value),
        values = this.values.map { context.interpolateNode(it) ?: JsonNull },
    )
}

/** Where a cursor marker points: a document, its path when known, and an LSP position in UTF-16 units. */
data class CursorLocation(val uri: String, val path: String?, val line: Int, val character: Int)

data class ScenarioState(var initializedResult: InitializeResult? = null)

class ScenarioWorkspace(val rootDir: java.nio.file.Path) {
    val rootUri: String = rootDir.toUri().toString()

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

        return when (expected) {
            is JsonPrimitive -> actual is JsonPrimitive && actual == expected
            is JsonObject -> {
                actual is JsonObject && expected.entries.all { (key, value) ->
                    actual.containsKey(key) && structurallyContains(actual[key], value)
                }
            }

            is JsonArray -> {
                actual is JsonArray &&
                    // All elements in expected must be structurally contained in some element of actual
                    expected.all { exp -> actual.any { act -> structurallyContains(act, exp) } }
            }
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
