package com.github.albertocavalcante.groovylsp.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.name

private val logger = LoggerFactory.getLogger(ScenarioExecutor::class.java)

class ScenarioExecutor(private val sessionFactory: LanguageServerSessionFactory, private val mapper: ObjectMapper) {
    private val jsonPathConfig: Configuration = Configuration.builder()
        .jsonProvider(JacksonJsonNodeJsonProvider(mapper))
        .mappingProvider(JacksonMappingProvider(mapper))
        .build()

    fun execute(definition: ScenarioDefinition) {
        val workspace = prepareWorkspace(definition)
        val scenario = definition.scenario

        val session = sessionFactory.start(scenario.server, scenario.name)

        try {
            val context = ScenarioContext(
                definition = definition,
                session = session,
                workspace = workspace,
                mapper = mapper,
                jsonPathConfig = jsonPathConfig,
            )
            context.registerBuiltInVariables()

            scenario.steps.forEachIndexed { index, step ->
                logger.info(
                    "Running step {} ({}) for scenario '{}'",
                    index + 1,
                    step::class.simpleName,
                    scenario.name,
                )
                context.currentStepIndex = index
                context.totalSteps = scenario.steps.size
                val nextStep = scenario.steps.getOrNull(index + 1)
                executeStep(step, nextStep, context)
            }
        } finally {
            session.close()
            workspace.cleanup()
        }
    }

    private fun executeStep(step: ScenarioStep, nextStep: ScenarioStep?, context: ScenarioContext) {
        when (step) {
            is ScenarioStep.Initialize -> performInitialize(step, context)
            ScenarioStep.Initialized -> performInitialized(context)
            ScenarioStep.Shutdown -> performShutdown(context)
            ScenarioStep.Exit -> performExit(context)
            is ScenarioStep.OpenDocument -> performOpenDocument(step, context)
            is ScenarioStep.ChangeDocument -> performChangeDocument(step, context)
            is ScenarioStep.SaveDocument -> performSaveDocument(step, context)
            is ScenarioStep.CloseDocument -> performCloseDocument(step, context)
            is ScenarioStep.SendRequest -> performSendRequest(step, context)
            is ScenarioStep.SendNotification -> performSendNotification(step, context)
            is ScenarioStep.WaitNotification -> performWaitNotification(step, nextStep, context)
            is ScenarioStep.Assert -> performAssert(step, context)
        }
    }

    private fun performInitialize(step: ScenarioStep.Initialize, context: ScenarioContext) {
        val interpolatedOptions = step.initializationOptions?.let { context.interpolateNode(it) }
        val params = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            @Suppress("DEPRECATION")
            rootUri = step.rootUri ?: context.workspace.rootUri
            @Suppress("DEPRECATION")
            workspaceFolders = if (rootUri != null) {
                listOf(WorkspaceFolder(rootUri, context.workspace.rootDir.name))
            } else {
                emptyList()
            }
            initializationOptions = interpolatedOptions?.let {
                mapper.convertValue(it, MutableMap::class.java)
            }
        }

        val result: InitializeResult = context.session.server.initialize(
            params,
        ).get(DEFAULT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        context.state.initializedResult = result
        context.lastResult = mapper.valueToTree(result)

        interpolatedOptions?.let {
            context.variables["client.initializationOptions"] = it
        }
    }

    private fun performInitialized(context: ScenarioContext) {
        val params = InitializedParams()
        context.session.server.initialized(params)
    }

    private fun performShutdown(context: ScenarioContext) {
        val timeout = System.getProperty("groovy.lsp.e2e.shutdownTimeoutMs")
            ?.toLongOrNull()
            ?: DEFAULT_SHUTDOWN_TIMEOUT_MS

        val start = System.nanoTime()
        context.session.server.shutdown().get(timeout, TimeUnit.MILLISECONDS)
        val elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis()
        logger.info("Shutdown completed in {} ms", elapsedMs)
    }

    private fun performExit(context: ScenarioContext) {
        context.session.server.exit()
    }

    private fun performOpenDocument(step: ScenarioStep.OpenDocument, context: ScenarioContext) {
        val uri = context.resolveUri(step.uri, step.path)
        val text = context.interpolateString(step.text)
        val languageId = context.interpolateString(step.languageId)

        val textDocument = TextDocumentItem().apply {
            this.uri = uri
            this.languageId = languageId
            this.version = step.version
            this.text = text
        }

        context.session.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(textDocument),
        )

        val documentNode = mapper.createObjectNode().apply {
            put("uri", uri)
            put("languageId", languageId)
            put("version", step.version)
            put("text", text)
        }
        context.lastResult = documentNode
    }

    private fun performChangeDocument(step: ScenarioStep.ChangeDocument, context: ScenarioContext) {
        val uri = context.resolveUri(step.uri, step.path)
        val identifier = VersionedTextDocumentIdentifier(uri, step.version)

        val changes = step.contentChanges.map { change ->
            val text = context.interpolateString(change.text)
            val range = change.range?.let {
                Range(
                    Position(it.start.line, it.start.character),
                    Position(it.end.line, it.end.character),
                )
            }
            TextDocumentContentChangeEvent().apply {
                this.range = range
                if (change.rangeLength != null) {
                    @Suppress("DEPRECATION")
                    this.rangeLength = change.rangeLength
                }
                this.text = text
            }
        }

        context.session.server.textDocumentService.didChange(
            DidChangeTextDocumentParams(identifier, changes),
        )

        context.lastResult = mapper.createObjectNode().apply {
            put("uri", uri)
            put("version", step.version)
            set<JsonNode>("changes", mapper.valueToTree(changes))
        }
    }

    private fun performSaveDocument(step: ScenarioStep.SaveDocument, context: ScenarioContext) {
        val uri = context.resolveUri(step.uri, step.path)
        val identifier = TextDocumentIdentifier(uri)
        val params = DidSaveTextDocumentParams(identifier).apply {
            text = step.text?.let { context.interpolateString(it) }
        }
        context.session.server.textDocumentService.didSave(params)
        context.lastResult = mapper.valueToTree(params)
    }

    private fun performCloseDocument(step: ScenarioStep.CloseDocument, context: ScenarioContext) {
        val uri = context.resolveUri(step.uri, step.path)
        val params = DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
        context.session.server.textDocumentService.didClose(params)
        context.lastResult = mapper.valueToTree(params)
    }

    private fun performSendNotification(step: ScenarioStep.SendNotification, context: ScenarioContext) {
        val paramsNode = context.interpolateNode(step.params)
        val paramsObject = paramsNode?.let { mapper.convertValue(it, Any::class.java) }
        context.session.endpoint.notify(step.method, paramsObject)
        context.lastResult = paramsNode ?: NullNode.instance
    }

    private fun performSendRequest(step: ScenarioStep.SendRequest, context: ScenarioContext) {
        val paramsNode = context.interpolateNode(step.params)
        val paramsObject = paramsNode?.let { mapper.convertValue(it, Any::class.java) }
        val timeout = step.timeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS

        val response = context.session.endpoint.request(step.method, paramsObject)
            .get(timeout, TimeUnit.MILLISECONDS)

        val responseNode = mapper.valueToTree<JsonNode?>(response) ?: NullNode.instance
        val normalized = context.normalizeResponse(step.method, responseNode)
        context.lastResult = normalized
        logger.info("Response for {}: {}", step.method, normalized)

        step.saveAs?.let { name ->
            context.saveResult(name, normalized)
        }

        if (step.extract.isNotEmpty()) {
            val document = JsonPath.using(jsonPathConfig).parse(normalized)
            step.extract.forEach { extraction ->
                val value = try {
                    document.read<Any?>(extraction.jsonPath)
                } catch (ex: PathNotFoundException) {
                    throw AssertionError("Extraction jsonPath '${extraction.jsonPath}' not found in response", ex)
                }
                context.setVariable(extraction.variable, mapper.valueToTree(value))
            }
        }
    }

    private fun performWaitNotification(
        step: ScenarioStep.WaitNotification,
        nextStep: ScenarioStep?,
        context: ScenarioContext,
    ) {
        // If this step is optional and the next step is a WaitNotification,
        // peek ahead to see if the next notification has already arrived
        if (step.optional && nextStep is ScenarioStep.WaitNotification) {
            val nextStepPredicate: (JsonNode?) -> Boolean = { payload ->
                nextStep.checks.all { check ->
                    runCatching {
                        context.evaluateCheck(payload ?: NullNode.instance, check, quiet = true)
                    }.getOrDefault(false)
                }
            }

            if (context.session.client.peekNotification(nextStep.method, nextStepPredicate)) {
                logger.info(
                    "Skipping optional step '{}' - next step's notification '{}' already available",
                    step.method,
                    nextStep.method,
                )
                return
            }
        }

        val timeout = step.timeoutMs ?: DEFAULT_NOTIFICATION_TIMEOUT_MS
        val checkFailureReasons = mutableListOf<String>() // Capture detailed check failure reasons

        val waitResult = try {
            context.session.client.awaitNotificationDetailed(step.method, timeout) { payload ->
                checkFailureReasons.clear() // Clear for each notification evaluation
                val allChecksPassed = step.checks.all { check ->
                    val result =
                        runCatching { context.evaluateCheck(payload ?: NullNode.instance, check, quiet = true) }
                    val success = result.getOrDefault(false)
                    if (!success) {
                        val failureReason = result.exceptionOrNull()?.message ?: "Check failed"
                        checkFailureReasons.add("${check.jsonPath} ${check.expect.type}: $failureReason")
                        logger.debug("Notification check failed: {}", failureReason)
                    }
                    success
                }
                allChecksPassed
            }
        } catch (e: TimeoutException) {
            // If optional, log and return early instead of failing the test
            if (step.optional) {
                logger.info("Optional step '{}' timed out after {}ms - continuing", step.method, timeout)
                return
            }
            throw e
        }

        val envelope = waitResult.envelope ?: run {
            if (step.optional) {
                logger.info("Optional step '{}' timed out after {}ms - continuing", step.method, timeout)
                return
            }

            val scenarioName = context.definition.scenario.name
            val stepNum = context.currentStepIndex + 1
            val totalSteps = context.totalSteps

            throw TimeoutException(
                buildString {
                    append("Timeout waiting for notification '${step.method}'")
                    append("\nScenario: $scenarioName (step $stepNum/$totalSteps: WaitNotification)")
                    append("\nTimeout: ${timeout}ms (elapsed: ${waitResult.elapsedMs}ms)")

                    if (waitResult.receivedDuringWait.isNotEmpty()) {
                        append("\n\nNotifications received during wait (${waitResult.receivedDuringWait.size} total):")
                        waitResult.receivedDuringWait.forEachIndexed { index, snapshot ->
                            val timestamp = snapshot.timestamp.toString().substringAfter('T').substringBefore('.')
                            append("\n  ${index + 1}. [$timestamp] ${snapshot.method}")

                            // Check if this notification matched method but failed predicate
                            val failed = waitResult.matchedMethodButFailed.find {
                                it.envelope.method == snapshot.method &&
                                    it.envelope.timestamp == snapshot.timestamp
                            }
                            if (failed != null) {
                                append("\n     → Failed check: ${failed.reason ?: "predicate returned false"}")

                                // Try to extract message content for window/showMessage notifications
                                if (snapshot.method == "window/showMessage" && snapshot.payload != null) {
                                    val message = snapshot.payload.get("message")?.asText()
                                    if (message != null) {
                                        append("\n     → Actual message: \"$message\"")
                                    }
                                }
                            }
                        }
                    } else {
                        append("\n\nNo notifications received during wait period.")
                    }

                    // Add total notification count from client
                    val totalNotifications = context.session.client.messages.size +
                        context.session.client.diagnostics.size
                    if (totalNotifications > 0) {
                        append("\n\nTotal notifications since scenario start: $totalNotifications")
                    }
                },
            )
        }

        val payloadNode = envelope.payload ?: NullNode.instance
        context.lastResult = payloadNode
        step.saveAs?.let { context.saveResult(it, payloadNode) }
        if (step.checks.isNotEmpty()) {
            step.checks.forEach { check ->
                context.evaluateCheck(payloadNode, check)
            }
        }
    }

    private fun performAssert(step: ScenarioStep.Assert, context: ScenarioContext) {
        val sourceNode = when {
            step.source == null -> context.lastResult ?: error("No previous result available for assertion")
            context.savedResults.containsKey(step.source) -> context.savedResults.getValue(step.source)
            context.variables.containsKey(step.source) -> context.variables.getValue(step.source)
            else -> error("Unknown result or variable '${step.source}' referenced in assert step")
        }
        step.checks.forEach { check ->
            try {
                context.evaluateCheck(sourceNode, check)
            } catch (e: AssertionError) {
                logger.error("Assertion failed in step: {}", step)
                logger.error("Source node: {}", sourceNode)
                throw e
            }
        }
    }

    private fun prepareWorkspace(definition: ScenarioDefinition): ScenarioWorkspace = ScenarioWorkspace(
        WorkspaceFixture.materialize(
            scenarioSource = definition.source,
            fixtureName = definition.scenario.workspace?.fixture,
        ),
    )

    companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        private const val DEFAULT_NOTIFICATION_TIMEOUT_MS = 30_000L
        private const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 30_000L
    }
}

private data class ScenarioContext(
    val definition: ScenarioDefinition,
    val session: LanguageServerSession,
    val workspace: ScenarioWorkspace,
    val mapper: ObjectMapper,
    val jsonPathConfig: Configuration,
) {
    val state: ScenarioState = ScenarioState()
    val variables: MutableMap<String, JsonNode> = mutableMapOf()
    val savedResults: MutableMap<String, JsonNode> = mutableMapOf()
    var lastResult: JsonNode? = null
    var currentStepIndex: Int = 0
    var totalSteps: Int = 0

    fun registerBuiltInVariables() {
        val workspaceObject = mapper.createObjectNode().apply {
            workspace.rootUri?.let { put("uri", it) }
            put("path", workspace.rootDir.toString())
        }
        variables["workspace"] = workspaceObject
        workspaceObject.get("uri")?.let { variables["workspace.uri"] = it }
        variables["workspace.path"] = workspaceObject.get("path")
    }

    fun resolveUri(uri: String?, path: String?): String = when {
        uri != null -> interpolateString(uri)
        path != null -> workspace.resolveUri(interpolateString(path))
        else -> error("Document reference must specify either uri or path")
    }

    fun saveResult(name: String, node: JsonNode) {
        savedResults[name] = node
        variables[name] = node
    }

    fun setVariable(name: String, node: JsonNode) {
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

    fun interpolateNode(node: JsonNode?): JsonNode? {
        if (node == null) return null
        return when {
            node.isTextual -> mapper.valueToTree(interpolateString(node.asText()))
            node.isArray -> mapper.createArrayNode().apply {
                node.forEach { add(interpolateNode(it)) }
            }

            node.isObject -> mapper.createObjectNode().apply {
                node.fields().forEachRemaining { (key, value) ->
                    set<JsonNode>(key, interpolateNode(value))
                }
            }

            else -> node
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
            val document = JsonPath.using(jsonPathConfig).parse(variableNode)
            val value = try {
                document.read<Any?>("$." + jsonPath)
            } catch (ex: PathNotFoundException) {
                throw IllegalArgumentException("Path '.$jsonPath' not found in variable '$variableName'", ex)
            }
            mapper.valueToTree<JsonNode?>(value) ?: NullNode.instance
        } else {
            variableNode
        }

        return when {
            targetNode.isTextual -> targetNode.asText()
            targetNode.isNumber -> targetNode.numberValue().toString()
            targetNode.isBoolean -> targetNode.booleanValue().toString()
            targetNode.isNull -> "null"
            else -> mapper.writeValueAsString(targetNode)
        }
    }

    fun evaluateCheck(node: JsonNode, check: JsonCheck, quiet: Boolean = false): Boolean {
        val document = JsonPath.using(jsonPathConfig).parse(node)
        val (pathExists, readValue) = try {
            true to document.read<Any?>(check.jsonPath)
        } catch (ex: PathNotFoundException) {
            false to null
        }

        val expectation = check.expect.interpolate(this)
        val messagePrefix = buildString {
            append("Assertion failed for jsonPath '${check.jsonPath}' with expectation ${expectation.type}")
            check.message?.let {
                append(" (")
                append(it)
                append(")")
            }
        }

        val actualNode = mapper.valueToTree<JsonNode?>(readValue)
        val comparator = JsonExpectationEvaluator(mapper)
        val success = comparator.matches(
            expectation = expectation,
            pathExists = pathExists,
            actualNode = actualNode,
        )

        if (!success) {
            if (!quiet) {
                val actualDescription = if (!pathExists) {
                    "path not found"
                } else {
                    mapper.writeValueAsString(actualNode)
                }
                throw AssertionError("$messagePrefix. Actual: $actualDescription")
            }
            return false
        }

        return true
    }

    companion object {
        private val PLACEHOLDER_REGEX = "\\{\\{([^}]+)}}".toRegex()
    }

    private fun JsonExpectation.interpolate(context: ScenarioContext): JsonExpectation = JsonExpectation(
        type = type,
        value = context.interpolateNode(value),
        values = values.map { context.interpolateNode(it) ?: NullNode.instance },
    )
}

private fun ScenarioContext.normalizeResponse(method: String, response: JsonNode): JsonNode {
    if (!response.isObject) {
        return response
    }
    val objectNode = response.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()

    // Normalize Either-style responses into a consistent shape
    val leftNode = objectNode.get("left")
    val rightNode = objectNode.get("right")
    val hasLeft = leftNode != null && !leftNode.isNull
    val hasRight = rightNode != null && !rightNode.isNull

    if (hasLeft && !hasRight && objectNode.size() == (if (rightNode != null) 2 else 1)) {
        return when {
            leftNode.isArray -> mapper.createObjectNode().apply {
                put("dataOrigin", "left")
                set<JsonNode>("items", leftNode)
            }

            else -> leftNode
        }
    }

    if (hasRight && !hasLeft && objectNode.size() == (if (leftNode != null) 2 else 1)) {
        return when {
            rightNode.isArray -> mapper.createObjectNode().apply {
                put("dataOrigin", "right")
                set<JsonNode>("items", rightNode)
            }

            else -> rightNode
        }
    }

    return objectNode
}

private data class ScenarioState(var initializedResult: InitializeResult? = null)

class ScenarioWorkspace(val rootDir: java.nio.file.Path) {
    val rootUri: String? = rootDir.toUri().toString()

    fun resolveUri(relative: String): String = rootDir.resolve(relative).normalize().toUri().toString()

    fun cleanup() {
        WorkspaceFixture.cleanup(rootDir)
    }
}

private class JsonExpectationEvaluator(private val mapper: ObjectMapper) {
    fun matches(expectation: JsonExpectation, pathExists: Boolean, actualNode: JsonNode?): Boolean =
        when (expectation.type) {
            ExpectationType.EXISTS -> pathExists
            ExpectationType.NOT_EXISTS -> !pathExists
            ExpectationType.EQUALS -> pathExists && equals(actualNode, expectation.value)
            ExpectationType.NOT_EQUALS -> !pathExists || !equals(actualNode, expectation.value)
            ExpectationType.CONTAINS -> pathExists && contains(actualNode, expectation.value)
            ExpectationType.CONTAINS_ANY -> pathExists && containsAny(actualNode, expectation.values)
            ExpectationType.CONTAINS_ALL -> pathExists && containsAll(actualNode, expectation.values)
            ExpectationType.MATCHES_REGEX -> pathExists && matchesRegex(actualNode, expectation.value)
            ExpectationType.SIZE -> pathExists && hasSize(actualNode, expectation.value)
            ExpectationType.EMPTY -> !pathExists || isEmpty(actualNode)
            ExpectationType.NOT_EMPTY -> pathExists && !isEmpty(actualNode)
        }

    private fun equals(actual: JsonNode?, expected: JsonNode?): Boolean = actual == expected

    private fun contains(actual: JsonNode?, expected: JsonNode?): Boolean {
        if (actual == null || expected == null) return false
        return when {
            actual.isTextual -> actual.asText().contains(expected.asText())
            actual.isArray -> actual.any { element -> element == expected }
            else -> false
        }
    }

    private fun containsAny(actual: JsonNode?, expected: List<JsonNode>): Boolean =
        expected.any { contains(actual, it) }

    private fun containsAll(actual: JsonNode?, expected: List<JsonNode>): Boolean =
        expected.all { contains(actual, it) }

    private fun matchesRegex(actual: JsonNode?, expected: JsonNode?): Boolean {
        if (actual == null || expected == null) return false
        val pattern = expected.asText()
        return try {
            Regex(pattern).containsMatchIn(actual.asText())
        } catch (ex: Exception) {
            false
        }
    }

    private fun hasSize(actual: JsonNode?, expected: JsonNode?): Boolean {
        if (actual == null || expected == null || !expected.isInt) return false
        val size = expected.asInt()
        return when {
            actual.isArray -> actual.size() == size
            actual.isTextual -> actual.asText().length == size
            actual.isObject -> actual.size() == size
            else -> false
        }
    }

    private fun isEmpty(actual: JsonNode?): Boolean = actual == null || actual.isNull ||
        (actual.isTextual && actual.asText().isEmpty()) ||
        (actual.isArray && actual.size() == 0) ||
        (actual.isObject && actual.size() == 0)
}
