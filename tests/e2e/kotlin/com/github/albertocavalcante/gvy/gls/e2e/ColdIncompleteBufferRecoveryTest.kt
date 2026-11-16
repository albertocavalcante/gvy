// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.Version
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real stdio sessions. Pure assertion mutations live in separate harness tests. */
@Execution(ExecutionMode.SAME_THREAD)
class ColdIncompleteBufferRecoveryTest {
    @TestFactory
    fun `cold recovery does not depend on completion warming another feature`(): List<DynamicTest> =
        listOf("lf" to "\n", "crlf" to "\r\n").flatMap { (label, newline) ->
            val cold = Feature.entries.map { feature ->
                dynamicTest("cold-$label-${feature.name.lowercase()}") {
                    replay("cold-$label-${feature.name.lowercase()}", newline, malformed = true, listOf(feature))
                }
            }
            cold + dynamicTest("valid-$label") {
                replay("valid-$label", newline, malformed = false, Feature.entries)
            }
        }

    private fun replay(name: String, newline: String, malformed: Boolean, features: List<Feature>) {
        val fixture = ColdRecoveryFixture(if (malformed) "Cold" else "Valid", malformed, newline)
        val planned = listOf("diagnostics.integrity") + features.flatMap { feature ->
            if (feature == Feature.Completion) {
                listOf("completion.enum")
            } else {
                listOf(
                    "${feature.name.lowercase()}.before",
                    "${feature.name.lowercase()}.after",
                )
            }
        }
        val definition = ScenarioDefinition(Scenario(name, steps = emptyList()), "ColdIncompleteBufferRecoveryTest.kt")
        val recorder = NamedObservationRecorder(planned, definition.source)
        val evidence = ConcurrentHashMap<String, JsonElement>()
        val execution = ScenarioExecution(Duration.ofSeconds(WHOLE_CASE_SECONDS))
        val failure = try {
            execution.run(name, recorder::executorClosed) {
                try {
                    execute(name, fixture, features, recorder, evidence)
                } finally {
                    recorder.workerFinished()
                }
            }
            null
        } catch (failure: AssertionError) {
            failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            failure
        } catch (failure: Exception) {
            failure
        }
        val outcome = recorder.finish(failure)
        val snapshot = evidence.toMutableMap()
        snapshot["complete"] = JsonPrimitive(
            outcome.cleanup == CleanupOutcome.Succeeded && outcome.completion == ScenarioCompletion.Completed &&
                snapshot.containsKey("bindings"),
        )
        ScenarioOutcomeReport.write(definition, outcome, JsonObject(snapshot))
        ScenarioOutcomeJUnit.report(outcome)
    }

    private fun execute(
        name: String,
        fixture: ColdRecoveryFixture,
        features: List<Feature>,
        recorder: NamedObservationRecorder,
        evidence: MutableMap<String, JsonElement>,
    ) {
        val workspace = ScenarioWorkspace(Files.createTempDirectory("gvy-cold-recovery-"))
        AutoCloseable { recorder.closeWorkspace(workspace::cleanup) }.use {
            val source = ColdRecoverySource(workspace.resolveUri("OrderService.groovy"), 1, fixture.text)
            evidence["source"] = JsonBridge.toJsonElement(source)
            assertTrue(!Files.exists(workspace.rootDir.resolve("OrderService.groovy")), "No valid disk seed")
            val launch = packagedLaunch()
            val session = LanguageServerSessionFactory(
                packagedLaunch = launch,
                evidencePolicy = SessionEvidencePolicy.Private,
            ).start(
                ServerConfig(env = mapOf("GRADLE_USER_HOME" to workspace.rootDir.resolve("gradle").toString())),
                name,
            )
            val requests = mutableListOf<ColdRecoveryRequest>()
            AutoCloseable {
                val wire = checkNotNull(session.snapshotWireEvidence())
                evidence["rawWire"] = JsonBridge.toJsonElement(wire)
                evidence["stderr"] = JsonBridge.toJsonElement(session.snapshotStderrEvidence())
                evidence["decodedRequests"] = buildJsonArray {
                    requests.forEach { request ->
                        add(
                            buildJsonObject {
                                put("method", request.method)
                                put("params", request.params)
                                put("result", request.result)
                            },
                        )
                    }
                }
                evidence["bindings"] = ColdRecoveryWire.bind(wire, source, requests)
            }.use {
                AutoCloseable { recorder.closeSession(session::close) }.use {
                    evidence["launch"] = JsonBridge.toJsonElement(session.runtimeEvidence)
                    val initialized = await(
                        session.server.initialize(
                            InitializeParams().apply {
                                capabilities = ClientCapabilities()
                                workspaceFolders = listOf(WorkspaceFolder(workspace.rootUri, "cold-recovery"))
                            },
                        ),
                    )
                    evidence["initialize"] = JsonBridge.toJsonElement(initialized)
                    val runtime = JsonBridge.toJsonElement(await(session.groovyServer.runtimeFacts())).jsonObject
                    evidence["runtimeFacts"] = runtime
                    val build = runtime.getValue("build").jsonObject
                    val process = runtime.getValue("runtime").jsonObject
                    assertEquals(
                        launch.expectedSource.commit,
                        build.getValue("commit").jsonPrimitive.content,
                    )
                    assertEquals(
                        checkNotNull(session.process).pid().toString(),
                        process.getValue("processId").jsonPrimitive.content,
                    )
                    assertEquals("false", build.getValue("dirty").jsonPrimitive.content)
                    assertEquals("known", process.getValue("javaState").jsonPrimitive.content)
                    assertEquals("known", process.getValue("heapState").jsonPrimitive.content)
                    assertTrue(process.getValue("maxHeapBytes").jsonPrimitive.long in 1..256L * 1024 * 1024)
                    val legend = checkNotNull(initialized.capabilities.semanticTokensProvider).legend
                    val encoding = initialized.capabilities.positionEncoding
                    require(encoding == null || encoding == "utf-16") { "Unsupported negotiated encoding" }
                    session.server.initialized(InitializedParams())
                    await(session.client.readyFuture)
                    recorder.executionStarted()
                    session.server.textDocumentService.didOpen(
                        DidOpenTextDocumentParams(TextDocumentItem(source.uri, "groovy", source.version, source.text)),
                    )
                    fun request(method: String, params: JsonObject): JsonElement {
                        val result = SdkResponseProjection.unwrappedValue(
                            await(session.endpoint.request(method, RawJsonRpcParams(params))),
                        )
                        requests += ColdRecoveryRequest(method, params, result)
                        return result
                    }
                    val terminal = request(
                        "groovy/awaitDiagnostics",
                        buildJsonObject {
                            put("uri", source.uri)
                            put("version", source.version)
                        },
                    ).jsonObject
                    observe(recorder, "diagnostics.integrity") { ColdRecoveryAssertions.diagnostics(terminal, source) }
                    for (feature in features) {
                        observeFeature(feature, fixture, source, legend, encoding, recorder, ::request)
                    }
                    session.server.textDocumentService.didClose(
                        DidCloseTextDocumentParams(TextDocumentIdentifier(source.uri)),
                    )
                    await(session.server.shutdown())
                    session.exit()
                }
            }
        }
    }

    private fun observeFeature(
        feature: Feature,
        fixture: ColdRecoveryFixture,
        source: ColdRecoverySource,
        legend: SemanticTokensLegend,
        encoding: String?,
        recorder: NamedObservationRecorder,
        request: (String, JsonObject) -> JsonElement,
    ) {
        val document = buildJsonObject { put("uri", source.uri) }
        fun params(position: org.eclipse.lsp4j.Position? = null): JsonObject = buildJsonObject {
            put("textDocument", document)
            position?.let { put("position", JsonBridge.toJsonElement(it)) }
        }
        when (feature) {
            Feature.Tokens -> {
                val result = request("textDocument/semanticTokens/full", params())
                val tokens = ColdRecoveryAssertions.tokens(result, legend, source.text, encoding)
                for ((suffix, anchor) in listOf("before" to fixture.before, "after" to fixture.after)) {
                    observe(recorder, "tokens.$suffix", fixture) {
                        ColdRecoveryAssertions.token(tokens, fixture, anchor)
                    }
                }
            }
            Feature.Hover -> for ((suffix, anchor) in listOf("before" to fixture.before, "after" to fixture.after)) {
                val result = request("textDocument/hover", params(fixture.nameRange(anchor).start))
                observe(recorder, "hover.$suffix", fixture) { ColdRecoveryAssertions.hover(result, fixture, anchor) }
            }
            Feature.Outline -> {
                val result = request("textDocument/documentSymbol", params())
                for ((suffix, anchor) in listOf("before" to fixture.before, "after" to fixture.after)) {
                    observe(recorder, "outline.$suffix", fixture) {
                        ColdRecoveryAssertions.outline(result, fixture, anchor)
                    }
                }
            }
            Feature.Completion -> {
                val result = request("textDocument/completion", params(fixture.completionPosition))
                observe(recorder, "completion.enum") { ColdRecoveryAssertions.completion(result) }
            }
        }
    }

    private fun observe(
        recorder: NamedObservationRecorder,
        id: String,
        fixture: ColdRecoveryFixture? = null,
        check: () -> Unit,
    ) {
        val observedFailure = if (fixture?.malformed == true) observedFailures[id] else null
        val xfail = observedFailure?.let { message ->
            Xfail(2082, "Intact declaration lost around incomplete member access", "^${Regex.escape(message)}$")
        }
        recorder.observe(JsonCheck("$.recovery", JsonExpectation(), id = id, xfail = xfail), check)
    }

    private fun packagedLaunch(): PackagedLaunch {
        val jar = resolveColdRecoveryArtifact(checkNotNull(System.getProperty("groovy.lsp.e2e.execJar")))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(jar).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return PackagedLaunch(
            javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName),
            jar = jar,
            expectedSha256 = digest.digest().joinToString("") { "%02x".format(it) },
            expectedSource = SourceStamp(Version.info.gitSha, Version.info.gitDirty != false),
            jvmSettings = JvmSettings(maxHeapMiB = 256, initialHeapMiB = 32),
        )
    }

    private fun <T> await(future: CompletableFuture<T>): T = try {
        future.get(REQUEST_SECONDS, TimeUnit.SECONDS)
    } finally {
        if (!future.isDone) future.cancel(true)
    }

    private enum class Feature { Tokens, Hover, Outline, Completion }

    private companion object {
        // Each exact failure executed in the qualifying packaged baseline; valid and completion controls stay required.
        // See https://github.com/albertocavalcante/gvy/issues/2082. Remove metadata when each check starts passing.
        val observedFailures = mapOf(
            "tokens.before" to "recovery: missing class token BeforeCold",
            "tokens.after" to "recovery: missing class token AfterCold",
            "hover.before" to "recovery: empty class hover BeforeCold",
            "hover.after" to "recovery: empty class hover AfterCold",
            "outline.before" to "recovery: missing outline class BeforeCold",
            "outline.after" to "recovery: missing outline class AfterCold",
        )
        const val REQUEST_SECONDS = 30L
        const val WHOLE_CASE_SECONDS = 120L
    }
}

internal fun resolveColdRecoveryArtifact(configuredPath: String): Path =
    checkNotNull(BazelRunfiles.resolve(configuredPath)).toRealPath()
