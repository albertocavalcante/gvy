package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Resolves the scenario directory path from system properties.
 *
 * Supports two modes:
 * - Direct path: `groovy.lsp.e2e.scenarioDir` points directly to the scenarios directory (Gradle)
 * - Marker file: `groovy.lsp.e2e.scenarioDir.marker` points to a file inside the directory (Bazel)
 *
 * Bazel doesn't have directory labels, so we pass a marker file path and derive the directory.
 */
internal fun resolveScenarioDir(): String {
    // Try direct path first (Gradle)
    System.getProperty("groovy.lsp.e2e.scenarioDir")?.let { return it }

    // Try marker file (Bazel) - derive directory from file path
    System.getProperty("groovy.lsp.e2e.scenarioDir.marker")?.let { markerPath ->
        return File(markerPath).parent
            ?: error("Could not derive directory from marker file: $markerPath")
    }

    error(
        "System property 'groovy.lsp.e2e.scenarioDir' not set. " +
            "Configure this system property in the test environment " +
            "(for example via -Dgroovy.lsp.e2e.scenarioDir=/path/to/e2e/resources/scenarios) " +
            "to point to the directory containing e2e scenario definitions.",
    )
}

/**
 * Kotlin DSL for creating E2E test scenarios programmatically.
 *
 * This DSL provides a type-safe, readable alternative to YAML-based scenarios.
 * It builds [ScenarioDefinition] objects that can be executed by [ScenarioExecutor].
 *
 * Example usage:
 * ```kotlin
 * val scenario = scenario {
 *     name("completion-test")
 *     description("Test completion for println")
 *     workspace("completion-basic")
 *
 *     initialize()
 *     initialized()
 *
 *     waitForServerReady()
 *
 *     openDocument("src/Main.groovy", "class Foo { prin }")
 *
 *     completion(path = "src/Main.groovy", line = 0, character = 17) {
 *         jsonPath("$.items") { notEmpty() }
 *     }
 *
 *     assertResponse {
 *         jsonPath("$.items[*].label") { contains("println") }
 *     }
 * }
 * ```
 */

/**
 * Main entry point for creating a scenario using the DSL.
 *
 * @param source Optional source path for the scenario. If provided, this is used to resolve
 *               workspace fixtures. If null, defaults to the e2e resources directory.
 */
fun scenario(source: String? = null, block: ScenarioBuilder.() -> Unit): ScenarioDefinition {
    val builder = ScenarioBuilder(source)
    builder.block()
    return builder.build()
}

/**
 * Builder for constructing a complete test scenario.
 */
class ScenarioBuilder(private val source: String? = null) {
    private var scenarioName: String = "unnamed-scenario"
    private var scenarioDescription: String? = null
    private var serverConfig: ServerConfig = ServerConfig()
    private var workspaceConfig: WorkspaceConfig? = null
    private val steps = mutableListOf<ScenarioStep>()

    /**
     * Set the scenario name (required).
     */
    fun name(name: String) {
        this.scenarioName = name
    }

    /**
     * Set the scenario description (optional).
     */
    fun description(desc: String) {
        this.scenarioDescription = desc
    }

    /**
     * Configure the server launch settings.
     */
    fun server(block: ServerConfigBuilder.() -> Unit) {
        val builder = ServerConfigBuilder()
        builder.block()
        this.serverConfig = builder.build()
    }

    /**
     * Set the workspace fixture name.
     */
    fun workspace(fixtureName: String) {
        this.workspaceConfig = WorkspaceConfig(fixture = fixtureName)
    }

    /**
     * Add an initialize step.
     */
    fun initialize(rootUri: String? = null, initializationOptions: JsonElement? = null) {
        steps.add(ScenarioStep.Initialize(rootUri = rootUri, initializationOptions = initializationOptions))
    }

    /**
     * Send initialized notification to the server (after initialize response).
     */
    fun initialized() {
        steps.add(ScenarioStep.Initialized)
    }

    /**
     * Wait for the Groovy LSP server to be ready (health=ok, quiescent=true).
     */
    fun waitForServerReady(timeoutMs: Long = 180000) {
        steps.add(
            ScenarioStep.WaitNotification(
                method = "groovy/status",
                checks = listOf(
                    JsonCheck(
                        jsonPath = "$.health",
                        expect = JsonExpectation(type = ExpectationType.EQUALS, value = JsonPrimitive("ok")),
                    ),
                    JsonCheck(
                        jsonPath = "$.quiescent",
                        expect = JsonExpectation(type = ExpectationType.EQUALS, value = JsonPrimitive(true)),
                    ),
                ),
                timeoutMs = timeoutMs,
            ),
        )
    }

    /**
     * Open a document in the workspace.
     *
     * @param path Relative path from workspace root (e.g., "src/Main.groovy")
     * @param text Initial content of the document
     * @param languageId Language identifier (default: "groovy")
     * @param version Document version (default: 1)
     */
    fun openDocument(path: String, text: String = "", languageId: String = "groovy", version: Int = 1) {
        steps.add(
            ScenarioStep.OpenDocument(
                path = path,
                languageId = languageId,
                version = version,
                text = text,
            ),
        )
    }

    /**
     * Change document content.
     */
    fun changeDocument(path: String, text: String, version: Int = 2) {
        steps.add(
            ScenarioStep.ChangeDocument(
                path = path,
                version = version,
                text = text,
            ),
        )
    }

    /**
     * Save a document.
     */
    fun saveDocument(path: String, text: String? = null) {
        steps.add(ScenarioStep.SaveDocument(path = path, text = text))
    }

    /**
     * Close a document.
     */
    fun closeDocument(path: String) {
        steps.add(ScenarioStep.CloseDocument(path = path))
    }

    /**
     * Send an LSP request and optionally save the response.
     *
     * Use the [RequestBuilder] to construct the request parameters.
     */
    fun request(method: String, saveAs: String? = null, block: RequestBuilder.() -> Unit = {}) {
        val builder = RequestBuilder(method)
        builder.block()
        steps.add(builder.build(saveAs))
    }

    /**
     * Send a notification (no response expected).
     */
    fun notification(method: String, block: RequestBuilder.() -> Unit = {}) {
        val builder = RequestBuilder(method)
        builder.block()
        steps.add(ScenarioStep.SendNotification(method = method, params = builder.buildParams()))
    }

    /**
     * Wait for a notification from the server.
     */
    fun waitNotification(
        method: String,
        saveAs: String? = null,
        optional: Boolean = false,
        timeoutMs: Long? = null,
        block: AssertionBuilder.() -> Unit = {},
    ) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(
            ScenarioStep.WaitNotification(
                method = method,
                checks = builder.buildChecks(),
                saveAs = saveAs,
                optional = optional,
                timeoutMs = timeoutMs,
            ),
        )
    }

    /**
     * Assert against a previously saved response or the last result.
     */
    fun assert(source: String? = null, block: AssertionBuilder.() -> Unit) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(ScenarioStep.Assert(source = source, checks = builder.buildChecks()))
    }

    /**
     * Convenience method to assert against the last response.
     */
    fun assertResponse(block: AssertionBuilder.() -> Unit) {
        assert(source = null, block = block)
    }

    /**
     * Request completion at a specific position.
     * @param path Document path
     * @param line Line number (0-based)
     * @param character Character offset (0-based)
     * @param block Optional assertion block to verify completion results
     */
    fun completion(path: String, line: Int, character: Int, block: AssertionBuilder.() -> Unit = {}) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(
            ScenarioStep.Completion(
                path = path,
                line = line,
                character = character,
                checks = builder.buildChecks(),
            ),
        )
    }

    /**
     * Request code actions for a range.
     * @param path Document path
     * @param range Selection range for code actions (defaults to (0,0)-(0,0))
     * @param block Optional assertion block to verify code action results
     */
    fun codeAction(path: String, range: TestRange? = null, block: AssertionBuilder.() -> Unit = {}) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(
            ScenarioStep.CodeAction(
                path = path,
                range = range,
                checks = builder.buildChecks(),
            ),
        )
    }

    /**
     * Request document formatting.
     * @param path Document path
     * @param tabSize Tab size for formatting (default: 4)
     * @param insertSpaces Use spaces instead of tabs (default: true)
     * @param block Optional assertion block to verify formatting results
     */
    fun formatting(
        path: String,
        tabSize: Int = 4,
        insertSpaces: Boolean = true,
        block: AssertionBuilder.() -> Unit = {},
    ) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(
            ScenarioStep.Formatting(
                path = path,
                tabSize = tabSize,
                insertSpaces = insertSpaces,
                checks = builder.buildChecks(),
            ),
        )
    }

    /**
     * Request symbol rename.
     * @param path Document path
     * @param line Line number (0-based)
     * @param character Character offset (0-based)
     * @param newName New symbol name
     * @param block Optional assertion block to verify rename results
     */
    fun rename(path: String, line: Int, character: Int, newName: String, block: AssertionBuilder.() -> Unit = {}) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(
            ScenarioStep.Rename(
                path = path,
                line = line,
                character = character,
                newName = newName,
                checks = builder.buildChecks(),
            ),
        )
    }

    /**
     * Wait for a specified duration in milliseconds.
     */
    fun waitMs(durationMs: Long) {
        steps.add(ScenarioStep.Wait(duration = durationMs))
    }

    /**
     * Shutdown the server gracefully.
     */
    fun shutdown() {
        steps.add(ScenarioStep.Shutdown)
    }

    /**
     * Exit the server.
     */
    fun exit() {
        steps.add(ScenarioStep.Exit)
    }

    /**
     * Download a Jenkins plugin for CLI testing.
     */
    fun downloadPlugin(
        id: String,
        version: String,
        source: PluginSource = PluginSource.JENKINS_RELEASES,
        saveAs: String? = null,
    ) {
        steps.add(
            ScenarioStep.DownloadPlugin(
                id = id,
                version = version,
                source = source,
                saveAs = saveAs,
            ),
        )
    }

    /**
     * Execute a CLI command.
     */
    fun cli(
        command: String,
        args: List<String> = emptyList(),
        saveAs: String? = null,
        expectExitCode: Int = 0,
        timeoutSeconds: Long = 60,
        block: AssertionBuilder.() -> Unit = {},
    ) {
        val builder = AssertionBuilder()
        builder.block()
        steps.add(
            ScenarioStep.CliCommand(
                command = command,
                args = args,
                saveAs = saveAs,
                checks = builder.buildChecks(),
                timeoutSeconds = timeoutSeconds,
                expectExitCode = expectExitCode,
            ),
        )
    }

    /**
     * Assert against golden files.
     */
    fun goldenAssert(actual: String, expected: String, mode: GoldenMode = GoldenMode.JSON, message: String? = null) {
        steps.add(
            ScenarioStep.GoldenAssert(
                actual = actual,
                expected = expected,
                mode = mode,
                message = message,
            ),
        )
    }

    fun build(): ScenarioDefinition {
        // For DSL scenarios, use the e2e/resources/scenarios directory as source
        // This allows WorkspaceFixture to find the workspaces directory
        val effectiveSource = source ?: run {
            val resourcesDir = resolveScenarioDir()
            // Synthetic path for DSL scenarios: Uses .yaml extension for WorkspaceFixture compatibility
            // (expects scenario source to have .yaml extension). The '_' prefix distinguishes DSL-based
            // scenarios from actual YAML files. This path is never read; it's just used for path resolution.
            "$resourcesDir/_dsl-scenarios.yaml"
        }

        return ScenarioDefinition(
            scenario = Scenario(
                name = scenarioName,
                description = scenarioDescription,
                server = serverConfig,
                workspace = workspaceConfig,
                steps = steps,
            ),
            source = effectiveSource,
        )
    }
}

/**
 * Builder for server configuration.
 */
class ServerConfigBuilder {
    private var args: List<String> = emptyList()
    private var env: Map<String, String> = emptyMap()
    private var mode: ServerLaunchMode = ServerLaunchMode.Stdio

    fun args(vararg args: String) {
        this.args = args.toList()
    }

    fun env(vararg pairs: Pair<String, String>) {
        this.env = pairs.toMap()
    }

    fun mode(mode: ServerLaunchMode) {
        this.mode = mode
    }

    fun build(): ServerConfig = ServerConfig(args = args, env = env, mode = mode)
}

/**
 * Builder for LSP request parameters.
 */
class RequestBuilder(private val method: String) {
    private val params = mutableMapOf<String, JsonElement>()
    private val extractions = mutableListOf<JsonExtraction>()
    private var timeoutMs: Long? = null

    /**
     * Set document position for requests like completion, hover, definition.
     * @param path Relative path from workspace root (e.g., "src/Main.groovy"). Leading '/' is ignored.
     * @param line Line number (0-based)
     * @param character Character offset (0-based)
     */
    fun position(path: String, line: Int, character: Int) {
        val normalizedPath = path.trimStart('/')
        val uri = "{{workspace.uri}}$normalizedPath"
        params["textDocument"] = buildJsonObject {
            put("uri", uri)
        }
        params["position"] = buildJsonObject {
            put("line", line)
            put("character", character)
        }
    }

    /**
     * Set a custom parameter.
     */
    fun param(key: String, value: JsonElement) {
        params[key] = value
    }

    /**
     * Extract a value from the response using JsonPath.
     */
    fun extract(variable: String, jsonPath: String) {
        extractions.add(JsonExtraction(variable = variable, jsonPath = jsonPath))
    }

    /**
     * Set request timeout in milliseconds.
     */
    fun timeout(ms: Long) {
        this.timeoutMs = ms
    }

    fun buildParams(): JsonElement? {
        if (params.isEmpty()) return null
        return buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
        }
    }

    fun build(saveAs: String? = null): ScenarioStep.SendRequest = ScenarioStep.SendRequest(
        method = method,
        params = buildParams(),
        saveAs = saveAs,
        extract = extractions,
        timeoutMs = timeoutMs,
    )
}

/**
 * Builder for response assertions using JsonPath.
 */
class AssertionBuilder {
    private val checks = mutableListOf<JsonCheck>()

    /**
     * Add a JsonPath assertion.
     */
    fun jsonPath(path: String, message: String? = null, block: JsonExpectationBuilder.() -> Unit) {
        val builder = JsonExpectationBuilder()
        builder.block()
        checks.add(JsonCheck(jsonPath = path, expect = builder.build(), message = message))
    }

    fun buildChecks(): List<JsonCheck> = checks
}

/**
 * Builder for JsonPath expectations.
 */
class JsonExpectationBuilder {
    private var type: ExpectationType = ExpectationType.EXISTS
    private var value: JsonElement? = null
    private var values: List<JsonElement> = emptyList()

    /**
     * Assert that the path exists.
     */
    fun exists() {
        type = ExpectationType.EXISTS
    }

    /**
     * Assert that the path does not exist.
     */
    fun notExists() {
        type = ExpectationType.NOT_EXISTS
    }

    /**
     * Assert that the value equals the expected value.
     */
    fun equals(expected: Any) {
        type = ExpectationType.EQUALS
        value = toJsonElement(expected)
    }

    /**
     * Assert that the value does not equal the expected value.
     */
    fun notEquals(expected: Any) {
        type = ExpectationType.NOT_EQUALS
        value = toJsonElement(expected)
    }

    /**
     * Assert that the value contains the expected value (for strings or arrays).
     */
    fun contains(expected: Any) {
        type = ExpectationType.CONTAINS
        value = toJsonElement(expected)
    }

    /**
     * Assert that the value does not contain the expected value.
     */
    fun notContains(expected: Any) {
        type = ExpectationType.NOT_CONTAINS
        value = toJsonElement(expected)
    }

    /**
     * Assert that the value contains any of the expected values.
     */
    fun containsAny(vararg expected: Any) {
        type = ExpectationType.CONTAINS_ANY
        values = expected.map { toJsonElement(it) }
    }

    /**
     * Assert that the value contains all of the expected values.
     */
    fun containsAll(vararg expected: Any) {
        type = ExpectationType.CONTAINS_ALL
        values = expected.map { toJsonElement(it) }
    }

    /**
     * Assert that the value matches the regex pattern.
     */
    fun matchesRegex(pattern: String) {
        type = ExpectationType.MATCHES_REGEX
        value = JsonPrimitive(pattern)
    }

    /**
     * Assert that the array/string/object has the specified size.
     */
    fun size(expectedSize: Int) {
        type = ExpectationType.SIZE
        value = JsonPrimitive(expectedSize)
    }

    /**
     * Assert that the value is empty.
     */
    fun empty() {
        type = ExpectationType.EMPTY
    }

    /**
     * Assert that the value is not empty.
     */
    fun notEmpty() {
        type = ExpectationType.NOT_EMPTY
    }

    /**
     * Assert that the numeric value is greater than or equal to the expected value.
     */
    fun gte(expected: Number) {
        type = ExpectationType.GTE
        value = JsonPrimitive(expected.toDouble())
    }

    /**
     * Assert that the numeric value is less than or equal to the expected value.
     */
    fun lte(expected: Number) {
        type = ExpectationType.LTE
        value = JsonPrimitive(expected.toDouble())
    }

    /**
     * Assert that any element in an array contains the substring.
     */
    fun anyContains(substring: String) {
        type = ExpectationType.ANY_CONTAINS
        value = JsonPrimitive(substring)
    }

    /**
     * Assert that no element in an array contains the substring.
     */
    fun noneContains(substring: String) {
        type = ExpectationType.NONE_CONTAINS
        value = JsonPrimitive(substring)
    }

    private fun toJsonElement(value: Any): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is JsonElement -> value
        else -> JsonPrimitive(value.toString())
    }

    fun build(): JsonExpectation = JsonExpectation(type = type, value = value, values = values)
}

/**
 * Helper to create a position.
 */
fun position(line: Int, character: Int): TestPosition = TestPosition(line = line, character = character)

/**
 * Helper to create a range.
 */
fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): TestRange = TestRange(
    start = TestPosition(line = startLine, character = startChar),
    end = TestPosition(line = endLine, character = endChar),
)
