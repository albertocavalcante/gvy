package com.github.albertocavalcante.groovylsp.e2e

import com.github.albertocavalcante.groovylsp.e2e.JsonBridge.toJavaObject
import com.github.albertocavalcante.groovylsp.e2e.JsonBridge.wrapJavaObject
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.name

private val logger = LoggerFactory.getLogger("StepExecutors")

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

        val result: InitializeResult = context.session.server.initialize(params)
            .get(30_000L, TimeUnit.MILLISECONDS)

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

        val response = context.session.endpoint.request(step.method, paramsObject)
            .get(timeout, TimeUnit.MILLISECONDS)

        val responseNode = wrapJavaObject(response) // Convert whatever Gson returned to JsonElement
        val normalized = context.normalizeResponse(step.method, responseNode)
        context.lastResult = normalized
        logger.info("Response for {}: {}", step.method, normalized)

        step.saveAs?.let { name ->
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
