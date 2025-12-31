package com.github.albertocavalcante.groovylsp.e2e

import com.github.albertocavalcante.groovylsp.e2e.JsonBridge.toJavaObject
import com.github.albertocavalcante.groovylsp.e2e.JsonBridge.wrapJavaObject
import com.github.albertocavalcante.groovylsp.testing.api.DiscoverTestsParams
import com.github.albertocavalcante.groovylsp.testing.api.GroovyLanguageServerProtocol
import com.github.albertocavalcante.groovylsp.testing.api.RunTestParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.name

private val logger = LoggerFactory.getLogger("StepExecutors")

// ============================================================================
// Custom Request Routing
// ============================================================================

/**
 * Sealed class for type-safe routing of custom @JsonRequest methods.
 *
 * LSP4J's generic [RemoteEndpoint.request] returns null for custom methods because
 * the client launcher doesn't know the expected response type. This sealed class
 * provides compile-time type safety when invoking custom methods through the
 * [GroovyLanguageServerProtocol] typed proxy.
 *
 * @see <a href="https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol">LSP4J Extending Protocol</a>
 */
sealed class CustomRequest<T> {
    abstract val method: String

    /**
     * Invoke this custom request on the typed server proxy.
     *
     * @param server The typed language server proxy
     * @param params Raw parameters from the scenario (will be converted to proper type)
     * @return CompletableFuture with the typed response
     */
    abstract fun invoke(server: GroovyLanguageServerProtocol, params: Any?): CompletableFuture<T>

    /**
     * Custom request: groovy/discoverTests
     */
    data object DiscoverTests : CustomRequest<List<com.github.albertocavalcante.groovylsp.testing.api.TestSuite>>() {
        override val method = "groovy/discoverTests"

        @Suppress("UNCHECKED_CAST")
        override fun invoke(
            server: GroovyLanguageServerProtocol,
            params: Any?,
        ): CompletableFuture<List<com.github.albertocavalcante.groovylsp.testing.api.TestSuite>> {
            val typedParams = params.toDiscoverTestsParams()
            return server.discoverTests(typedParams)
        }
    }

    /**
     * Custom request: groovy/runTest
     */
    data object RunTest : CustomRequest<com.github.albertocavalcante.groovylsp.testing.api.TestCommand>() {
        override val method = "groovy/runTest"

        @Suppress("UNCHECKED_CAST")
        override fun invoke(
            server: GroovyLanguageServerProtocol,
            params: Any?,
        ): CompletableFuture<com.github.albertocavalcante.groovylsp.testing.api.TestCommand> {
            val typedParams = params.toRunTestParams()
            return server.runTest(typedParams)
        }
    }

    companion object {
        /**
         * Look up a CustomRequest by method name.
         *
         * @return The CustomRequest if this is a known custom method, null otherwise
         */
        fun fromMethod(method: String): CustomRequest<*>? = when (method) {
            DiscoverTests.method -> DiscoverTests
            RunTest.method -> RunTest
            else -> null
        }
    }
}

/**
 * Convert raw params Map to [DiscoverTestsParams].
 */
@Suppress("UNCHECKED_CAST")
private fun Any?.toDiscoverTestsParams(): DiscoverTestsParams {
    val map = this as? Map<String, Any?> ?: error("Expected Map for DiscoverTestsParams, got: $this")
    return DiscoverTestsParams(
        workspaceUri = map["workspaceUri"] as? String ?: error("Missing workspaceUri in DiscoverTestsParams"),
    )
}

/**
 * Convert raw params Map to [RunTestParams].
 */
@Suppress("UNCHECKED_CAST")
private fun Any?.toRunTestParams(): RunTestParams {
    val map = this as? Map<String, Any?> ?: error("Expected Map for RunTestParams, got: $this")
    return RunTestParams(
        uri = map["uri"] as? String ?: error("Missing uri in RunTestParams"),
        suite = map["suite"] as? String ?: error("Missing suite in RunTestParams"),
        test = map["test"] as? String,
        debug = map["debug"] as? Boolean ?: false,
    )
}

interface StepExecutor<T : ScenarioStep> {
    fun execute(step: T, context: ScenarioContext, nextStep: ScenarioStep? = null)
}

class InitializeStepExecutor : StepExecutor<ScenarioStep.Initialize> {
    override fun execute(step: ScenarioStep.Initialize, context: ScenarioContext, nextStep: ScenarioStep?) {
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
                // Convert to Java Map/Object for LSP4J
                it.toJavaObject()
            }
        }

        val future = context.session.server.initialize(params)

        val timeoutMs = 30_000L
        val start = System.currentTimeMillis()
        val process = context.session.process

        while (!future.isDone) {
            if (process != null && !process.isAlive) {
                // If the process has exited, we can't possibly succeed.
                // Read stderr to capture the error reason if possible (though stderr pump is async)
                throw IllegalStateException(
                    "Language server process exited prematurely with code ${process.exitValue()} while waiting for initialization",
                )
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw TimeoutException("Timed out waiting for initialize response ($timeoutMs ms)")
            }
            // Sleep briefly to avoid busy wait
            Thread.sleep(50)
        }

        val result: InitializeResult = future.get()

        context.state.initializedResult = result
        // Serialize LSP4J result back to JsonElement using Gson bridge
        context.lastResult = JsonBridge.toJsonElement(result)

        interpolatedOptions?.let {
            context.variables["client.initializationOptions"] = it
        }
    }
}

class InitializedStepExecutor : StepExecutor<ScenarioStep.Initialized> {
    override fun execute(step: ScenarioStep.Initialized, context: ScenarioContext, nextStep: ScenarioStep?) {
        context.session.server.initialized(InitializedParams())
    }
}

class ShutdownStepExecutor : StepExecutor<ScenarioStep.Shutdown> {
    override fun execute(step: ScenarioStep.Shutdown, context: ScenarioContext, nextStep: ScenarioStep?) {
        val timeout = System.getProperty("groovy.lsp.e2e.shutdownTimeoutMs")
            ?.toLongOrNull() ?: 60_000L

        val start = System.nanoTime()
        context.session.server.shutdown().get(timeout, TimeUnit.MILLISECONDS)
        val elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis()
        logger.info("Shutdown completed in {} ms", elapsedMs)
    }
}

class ExitStepExecutor : StepExecutor<ScenarioStep.Exit> {
    override fun execute(step: ScenarioStep.Exit, context: ScenarioContext, nextStep: ScenarioStep?) {
        context.session.server.exit()
    }
}

class OpenDocumentStepExecutor : StepExecutor<ScenarioStep.OpenDocument> {
    override fun execute(step: ScenarioStep.OpenDocument, context: ScenarioContext, nextStep: ScenarioStep?) {
        val uri = context.resolveUri(step.uri, step.path)
        val text = context.interpolateString(step.text)
        val languageId = context.interpolateString(step.languageId)

        val textDocument = TextDocumentItem().apply {
            this.uri = uri
            this.languageId = languageId
            this.version = step.version
            this.text = text
        }

        context.session.server.textDocumentService.didOpen(DidOpenTextDocumentParams(textDocument))

        context.lastResult = buildJsonObject {
            put("uri", uri)
            put("languageId", languageId)
            put("version", step.version)
            put("text", text)
        }
    }
}

class ChangeDocumentStepExecutor : StepExecutor<ScenarioStep.ChangeDocument> {
    override fun execute(step: ScenarioStep.ChangeDocument, context: ScenarioContext, nextStep: ScenarioStep?) {
        val uri = context.resolveUri(step.uri, step.path)
        val identifier = VersionedTextDocumentIdentifier(uri, step.version)

        // If text field is provided and contentChanges is empty, treat as full-document replacement
        val changes: List<org.eclipse.lsp4j.TextDocumentContentChangeEvent> =
            if (step.text != null && step.contentChanges.isEmpty()) {
                val interpolatedText = context.interpolateString(step.text)
                // Full document replacement - use simpler constructor (no range = replace all)
                listOf(org.eclipse.lsp4j.TextDocumentContentChangeEvent(interpolatedText))
            } else {
                step.contentChanges.map { change ->
                    val text = context.interpolateString(change.text)
                    val lsp4jRange = change.range?.let {
                        Range(
                            Position(it.start.line, it.start.character),
                            Position(it.end.line, it.end.character),
                        )
                    }
                    @Suppress("DEPRECATION")
                    org.eclipse.lsp4j.TextDocumentContentChangeEvent(lsp4jRange, change.rangeLength ?: 0, text)
                }
            }

        context.session.server.textDocumentService.didChange(DidChangeTextDocumentParams(identifier, changes))

        context.lastResult = buildJsonObject {
            put("uri", uri)
            put("version", step.version)
            put("changes", JsonBridge.toJsonElement(changes))
        }
    }
}

class SaveDocumentStepExecutor : StepExecutor<ScenarioStep.SaveDocument> {
    override fun execute(step: ScenarioStep.SaveDocument, context: ScenarioContext, nextStep: ScenarioStep?) {
        val uri = context.resolveUri(step.uri, step.path)
        val identifier = TextDocumentIdentifier(uri)
        val params = DidSaveTextDocumentParams(identifier).apply {
            text = step.text?.let { context.interpolateString(it) }
        }
        context.session.server.textDocumentService.didSave(params)
        context.lastResult = JsonBridge.toJsonElement(params)
    }
}

class CloseDocumentStepExecutor : StepExecutor<ScenarioStep.CloseDocument> {
    override fun execute(step: ScenarioStep.CloseDocument, context: ScenarioContext, nextStep: ScenarioStep?) {
        val uri = context.resolveUri(step.uri, step.path)
        val params = DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
        context.session.server.textDocumentService.didClose(params)
        context.lastResult = JsonBridge.toJsonElement(params)
    }
}

class SendNotificationStepExecutor : StepExecutor<ScenarioStep.SendNotification> {
    override fun execute(step: ScenarioStep.SendNotification, context: ScenarioContext, nextStep: ScenarioStep?) {
        val paramsNode = context.interpolateNode(step.params)
        val paramsObject = paramsNode?.toJavaObject()
        context.session.endpoint.notify(step.method, paramsObject)
        context.lastResult = paramsNode ?: JsonNull
    }
}

class SendRequestStepExecutor : StepExecutor<ScenarioStep.SendRequest> {
    override fun execute(step: ScenarioStep.SendRequest, context: ScenarioContext, nextStep: ScenarioStep?) {
        val paramsNode = context.interpolateNode(step.params)
        val paramsObject = paramsNode?.toJavaObject()
        val timeout = step.timeoutMs ?: 30_000L

        logger.info("Sending request '{}' with params: {}", step.method, paramsObject)

        // Route custom @JsonRequest methods through typed proxy for proper response deserialization.
        // Standard LSP methods use the generic endpoint.request() fallback.
        //
        // LSP4J's generic endpoint.request() returns null for custom methods because the
        // client launcher doesn't know the expected response type. The typed server proxy
        // (GroovyLanguageServerProtocol) solves this by providing compile-time type information.
        //
        // @see https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol
        val customRequest = CustomRequest.fromMethod(step.method)

        val response = try {
            if (customRequest != null) {
                logger.info("Using typed proxy for custom method '{}'", step.method)
                val future = customRequest.invoke(context.session.groovyServer, paramsObject)
                logger.info("Custom request future created for '{}', waiting for response...", step.method)
                val result = future.get(timeout, TimeUnit.MILLISECONDS)
                logger.info("Custom request '{}' completed successfully", step.method)
                result
            } else {
                // Standard LSP method - use generic endpoint
                val future = context.session.endpoint.request(step.method, paramsObject)
                logger.info("Request future created for '{}', waiting for response...", step.method)
                val result = future.get(timeout, TimeUnit.MILLISECONDS)
                logger.info("Request '{}' completed successfully", step.method)
                result
            }
        } catch (e: Exception) {
            logger.error("Request '{}' failed with exception: {} - {}", step.method, e::class.simpleName, e.message)
            throw e
        }

        logger.info("Raw response for {}: {} (type={})", step.method, response, response?.javaClass?.simpleName)
        val responseNode = wrapJavaObject(response) // Convert whatever Gson returned to JsonElement
        val normalized = context.normalizeResponse(step.method, responseNode)
        context.lastResult = normalized
        logger.info("Response for {}: {}", step.method, normalized)

        step.saveAs?.let { name ->
            logger.info("Saving result as '{}': {}", name, normalized)
            context.saveResult(name, normalized)
        }

        if (step.extract.isNotEmpty()) {
            // Re-parse normalized JsonElement to Java Object for generic JsonPath usage
            // (since we don't have a direct JsonElement -> JsonPath adapter that is public yet)
            // Actually, we can just use the response object if it was already a Map/List?
            // Normalized might have changed it. Safer to use normalized.
            // But context.evaluateCheck uses toJavaObject internaly implicitly?
            // No, verify context.evaluateCheck. It does `node.toJavaObject()`.
            // So we can use `normalized` (JsonElement).

            // Wait, for step.extract, we are extracting variables.
            // context.evaluateCheck is for asserts.
            // step.extract needs manual JsonPath.
            // We can reuse the logic from `context.resolveExpression`-ish logic?
            // Or just do what ScenarioLoader did?

            // Let's implement extraction using the same pattern as evaluateCheck
            val javaObject = normalized.toJavaObject()
            val document = com.jayway.jsonpath.JsonPath.using(context.jsonPathConfig).parse(javaObject)

            step.extract.forEach { extraction ->
                val value = try {
                    document.read<Any?>(extraction.jsonPath)
                } catch (ex: com.jayway.jsonpath.PathNotFoundException) {
                    throw AssertionError("Extraction jsonPath '${extraction.jsonPath}' not found in response", ex)
                }
                context.setVariable(extraction.variable, wrapJavaObject(value))
            }
        }
    }
}

class WaitNotificationStepExecutor : StepExecutor<ScenarioStep.WaitNotification> {
    override fun execute(step: ScenarioStep.WaitNotification, context: ScenarioContext, nextStep: ScenarioStep?) {
        // Optimization: Peek ahead if optional and next step is also a wait
        if (step.optional && nextStep is ScenarioStep.WaitNotification) {
            val nextStepPredicate: (Any?) -> Boolean = { payload ->
                // payload comes from HarnessLanguageClient. Currently it is Any? (LSP4J uses Objects).
                // We need to convert it to JsonElement to check.
                val jsonPayload = wrapJavaObject(payload)
                nextStep.checks.all { check ->
                    runCatching {
                        context.evaluateCheck(jsonPayload, check, quiet = true)
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

        val timeout = step.timeoutMs ?: 30_000L
        val checkFailureReasons = mutableListOf<String>()

        val earlySkipPredicate: (() -> Boolean)? = if (step.optional && nextStep is ScenarioStep.WaitNotification) {
            val nextStepPredicate: (Any?) -> Boolean = { payload ->
                val jsonPayload = wrapJavaObject(payload)
                nextStep.checks.all { check ->
                    runCatching {
                        context.evaluateCheck(jsonPayload, check, quiet = true)
                    }.getOrDefault(false)
                }
            }
            { context.session.client.peekNotification(nextStep.method, nextStepPredicate) }
        } else {
            null
        }

        val waitResult = try {
            context.session.client.awaitNotificationDetailed(
                step.method,
                timeout,
                { payload ->
                    checkFailureReasons.clear()
                    val jsonPayload = wrapJavaObject(payload)
                    step.checks.all { check ->
                        val result =
                            runCatching { context.evaluateCheck(jsonPayload, check, quiet = true) }
                        val success = result.getOrDefault(false)
                        if (!success) {
                            val failureReason = result.exceptionOrNull()?.message ?: "Check failed"
                            checkFailureReasons.add("${check.jsonPath} ${check.expect.type}: $failureReason")
                            logger.debug("Notification check failed: {}", failureReason)
                        }
                        success
                    }
                },
                earlySkipPredicate,
            )
        } catch (e: TimeoutException) {
            if (step.optional) {
                logger.info("Optional step '{}' timed out after {}ms - continuing", step.method, timeout)
                return
            }
            throw e
        }

        if (waitResult.skipped) {
            logger.info("Skipping optional step '{}' - next notification arrived", step.method)
            return
        }

        val envelope = waitResult.envelope ?: run {
            if (step.optional) return
            throw TimeoutException("Timeout waiting for notification '${step.method}'")
        }

        val payloadNode = wrapJavaObject(envelope.payload)
        context.lastResult = payloadNode
        step.saveAs?.let { context.saveResult(it, payloadNode) }
        step.checks.forEach { check ->
            context.evaluateCheck(payloadNode, check)
        }
    }
}

class AssertStepExecutor : StepExecutor<ScenarioStep.Assert> {
    override fun execute(step: ScenarioStep.Assert, context: ScenarioContext, nextStep: ScenarioStep?) {
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
}

class WaitStepExecutor : StepExecutor<ScenarioStep.Wait> {
    override fun execute(step: ScenarioStep.Wait, context: ScenarioContext, nextStep: ScenarioStep?) {
        Thread.sleep(step.duration)
        logger.info("Waited for {} ms", step.duration)
    }
}

class DownloadPluginStepExecutor : StepExecutor<ScenarioStep.DownloadPlugin> {
    override fun execute(step: ScenarioStep.DownloadPlugin, context: ScenarioContext, nextStep: ScenarioStep?) {
        val pluginId = step.id
        val version = step.version

        // Define cache dir relative to project or in standard location
        // For E2E tests, we use a shared cache to speed up tests
        val cacheDir = java.nio.file.Path.of(System.getProperty("user.home"), ".gls", "jenkins-cache")

        val downloader = com.github.albertocavalcante.groovyjenkins.extraction.PluginDownloader(cacheDir)

        try {
            logger.info("Downloading plugin {}:{}", pluginId, version)
            val path = downloader.download(pluginId, version)

            step.saveAs?.let { variableName ->
                context.setVariable(variableName, wrapJavaObject(path.toString()))
            }
            context.lastResult = buildJsonObject {
                put("plugin", pluginId)
                put("version", version)
                put("path", path.toString())
            }
        } catch (e: Exception) {
            throw AssertionError("Failed to download plugin $pluginId:$version", e)
        }
    }
}

class CliCommandStepExecutor : StepExecutor<ScenarioStep.CliCommand> {
    override fun execute(step: ScenarioStep.CliCommand, context: ScenarioContext, nextStep: ScenarioStep?) {
        val interpolatedCommand = context.interpolateString(step.command)
        val interpolatedArgs = step.args.map { context.interpolateString(it) }

        val fullCommand = if (interpolatedCommand.startsWith("gls") || interpolatedCommand.startsWith("jenkins")) {
            // We use the property "groovy.lsp.binary" which should be set by the test runner
            // Fallback to local build path for local dev
            val binaryPath = System.getProperty("groovy.lsp.binary")
                ?: "./groovy-lsp/build/install/groovy-lsp/bin/groovy-lsp"

            val cmd = mutableListOf(binaryPath)
            if (interpolatedCommand.contains(" ")) {
                val parts = interpolatedCommand.split(" ")
                if (parts.first() == "gls") {
                    cmd.addAll(parts.drop(1))
                } else {
                    cmd.addAll(parts)
                }
            } else if (interpolatedCommand != "gls") {
                cmd.add(interpolatedCommand)
            }
            cmd.addAll(interpolatedArgs)
            cmd
        } else {
            interpolatedCommand.split(" ") + interpolatedArgs
        }

        logger.info("Executing CLI command: {}", fullCommand.joinToString(" "))

        val builder = ProcessBuilder(fullCommand)
        builder.directory(context.workspace.rootDir.toFile())

        // Capture output
        val outputFile = java.io.File.createTempFile("cli-stdout", ".log")
        val errorFile = java.io.File.createTempFile("cli-stderr", ".log")
        builder.redirectOutput(outputFile)
        builder.redirectError(errorFile)

        val process = builder.start()

        val completed = process.waitFor(step.timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw TimeoutException("CLI command timed out after ${step.timeoutSeconds}s: $fullCommand")
        }

        val exitCode = process.exitValue()
        val stdout = outputFile.readText()
        val stderr = errorFile.readText()

        logger.info("CLI exit code: {}", exitCode)
        if (stderr.isNotEmpty()) {
            logger.warn("CLI stderr: {}", stderr)
        }

        if (exitCode != step.expectExitCode) {
            throw AssertionError(
                "CLI command failed with exit code $exitCode (expected ${step.expectExitCode})\nStdout: $stdout\nStderr: $stderr",
            )
        }

        context.lastResult = buildJsonObject {
            put("exitCode", exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
        }

        step.saveAs?.let { name ->
            context.saveResult(name, context.lastResult!!)
        }

        step.checks.forEach { check ->
            context.evaluateCheck(context.lastResult!!, check)
        }
    }
}

class GoldenAssertStepExecutor : StepExecutor<ScenarioStep.GoldenAssert> {
    override fun execute(step: ScenarioStep.GoldenAssert, context: ScenarioContext, nextStep: ScenarioStep?) {
        val actualPathString = context.interpolateString(step.actual)
        val expectedRelPath = context.interpolateString(step.expected)

        val actualFile = java.nio.file.Path.of(actualPathString)
        // Resolve golden file relative to the project root or resources dir
        val expectedFile = java.nio.file.Path.of("e2e/resources/golden", expectedRelPath).toAbsolutePath()

        // Ensure actual file exists
        if (!java.nio.file.Files.exists(actualFile)) {
            throw AssertionError("Actual file not found for golden assert: $actualFile")
        }

        // Logic to update golden files
        val updateSnapshot = System.getProperty("groovy.lsp.e2e.updateGolden") == "true"

        if (updateSnapshot) {
            logger.warn("Updating golden file: {}", expectedFile)
            java.nio.file.Files.createDirectories(expectedFile.parent)
            java.nio.file.Files.copy(actualFile, expectedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return
        }

        if (!java.nio.file.Files.exists(expectedFile)) {
            throw AssertionError(
                "Golden file not found: $expectedFile. Run with -Dgroovy.lsp.e2e.updateGolden=true to create it.",
            )
        }

        val actualContent = java.nio.file.Files.readString(actualFile)
        val expectedContent = java.nio.file.Files.readString(expectedFile)

        when (step.mode) {
            GoldenMode.JSON -> {
                // Compare as JSON trees to ignore formatting differences
                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                val actualJson = mapper.readTree(actualContent)
                val expectedJson = mapper.readTree(expectedContent)

                if (actualJson != expectedJson) {
                    // If different, show diff
                    throw AssertionError(
                        "JSON content mismatch for $expectedRelPath!\nExpected:\n$expectedContent\nActual:\n$actualContent",
                    )
                }
            }

            GoldenMode.TEXT -> {
                // Compare normalized text (trim lines)
                if (actualContent.trim() != expectedContent.trim()) {
                    throw AssertionError(
                        "Text content mismatch for $expectedRelPath!\nExpected:\n$expectedContent\nActual:\n$actualContent",
                    )
                }
            }

            GoldenMode.BINARY -> {
                // Direct byte comparison
                val actualBytes = java.nio.file.Files.readAllBytes(actualFile)
                val expectedBytes = java.nio.file.Files.readAllBytes(expectedFile)
                if (!java.util.Arrays.equals(actualBytes, expectedBytes)) {
                    throw AssertionError("Binary content mismatch for $expectedRelPath!")
                }
            }
        }
        logger.info("Golden verification passed for {}", expectedFile.fileName)
    }
}
