// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import kotlin.test.assertTrue

/** One serialized editing history per fresh process, with current-source facts at every revision. */
@Execution(ExecutionMode.SAME_THREAD)
class WarmIncompleteBufferRecoveryTest {
    @TestFactory
    fun `editor facts follow valid hole prefix and restored text`(): List<DynamicTest> =
        listOf("lf" to "\n", "crlf" to "\r\n").map { (label, newline) ->
            dynamicTest("warm-$label") { replay("warm-$label", newline) }
        }

    private fun replay(name: String, newline: String) {
        val states = states(newline)
        val planned = states.flatMap { state -> checks.map { "${state.label}.$it" } }
        RecoveryReplay.run(name, "WarmIncompleteBufferRecoveryTest.kt", planned) { recorder, evidence ->
            execute(name, states, recorder, evidence)
        }
    }

    private fun execute(
        name: String,
        states: List<State>,
        recorder: NamedObservationRecorder,
        evidence: MutableMap<String, JsonElement>,
    ) {
        val workspace = ScenarioWorkspace(Files.createTempDirectory("gvy-warm-recovery-"))
        AutoCloseable { recorder.closeWorkspace(workspace::cleanup) }.use {
            val uri = workspace.resolveUri("OrderService.groovy")
            val plannedSources = states.map { ColdRecoverySource(uri, it.version, it.fixture.text) }
            evidence["plannedSources"] = JsonBridge.toJsonElement(plannedSources)
            assertTrue(!Files.exists(workspace.rootDir.resolve("OrderService.groovy")), "No valid disk seed")
            val launch = RecoveryReplay.launch()
            val session = LanguageServerSessionFactory(
                packagedLaunch = launch,
                evidencePolicy = SessionEvidencePolicy.Private,
            ).start(
                ServerConfig(env = mapOf("GRADLE_USER_HOME" to workspace.rootDir.resolve("gradle").toString())),
                name,
            )
            val authoredSources = mutableListOf<ColdRecoverySource>()
            val requests = mutableListOf<WarmRecoveryRequest>()
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
                                put("source", JsonBridge.toJsonElement(request.source))
                            },
                        )
                    }
                }
                evidence["bindings"] = WarmRecoveryWire.bind(wire, authoredSources, requests)
            }.use {
                AutoCloseable { recorder.closeSession(session::close) }.use {
                    val initialized = RecoveryReplay.initialize(
                        session,
                        workspace,
                        launch,
                        evidence,
                        workspaceName = "warm-recovery",
                    )
                    val legend = checkNotNull(initialized.capabilities.semanticTokensProvider).legend
                    val encoding = initialized.capabilities.positionEncoding
                    recorder.executionStarted()
                    for ((index, state) in states.withIndex()) {
                        val source = plannedSources[index]
                        if (index == 0) {
                            session.server.textDocumentService.didOpen(
                                DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", source.version, source.text)),
                            )
                        } else {
                            session.server.textDocumentService.didChange(
                                DidChangeTextDocumentParams(
                                    VersionedTextDocumentIdentifier(uri, source.version),
                                    listOf(TextDocumentContentChangeEvent(source.text)),
                                ),
                            )
                        }
                        authoredSources += source
                        fun request(method: String, params: JsonObject): JsonElement {
                            val result = SdkResponseProjection.unwrappedValue(
                                RecoveryReplay.await(session.endpoint.request(method, RawJsonRpcParams(params))),
                            )
                            requests += WarmRecoveryRequest(source, method, params, result)
                            return result
                        }
                        val terminal = request(
                            "groovy/awaitDiagnostics",
                            buildJsonObject {
                                put("uri", uri)
                                put("version", source.version)
                            },
                        ).jsonObject
                        observe(recorder, state, "diagnostics.integrity") {
                            ColdRecoveryAssertions.diagnostics(terminal, source)
                        }
                        observeFeatures(state, states.take(index), source, legend, encoding, recorder, ::request)
                    }
                    session.server.textDocumentService.didClose(
                        DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
                    )
                    RecoveryReplay.await(session.server.shutdown())
                    session.exit()
                }
            }
        }
    }

    private fun observeFeatures(
        state: State,
        previous: List<State>,
        source: ColdRecoverySource,
        legend: SemanticTokensLegend,
        encoding: String?,
        recorder: NamedObservationRecorder,
        request: (String, JsonObject) -> JsonElement,
    ) {
        val fixture = state.fixture
        val oldNames = previous.flatMap { listOf(it.fixture.before, it.fixture.after) }.toSet()
        fun params(position: Position? = null): JsonObject = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", source.uri) })
            position?.let { put("position", JsonBridge.toJsonElement(it)) }
        }
        val tokens = ColdRecoveryAssertions.tokens(
            request("textDocument/semanticTokens/full", params()),
            legend,
            source.text,
            encoding,
        )
        for ((suffix, anchor) in listOf("before" to fixture.before, "after" to fixture.after)) {
            observe(recorder, state, "tokens.$suffix") {
                assertTrue(tokens.none { it.text in oldNames }, "Stale warm token identity")
                ColdRecoveryAssertions.token(tokens, fixture, anchor)
            }
        }
        for ((suffix, anchor) in listOf("before" to fixture.before, "after" to fixture.after)) {
            val result = request("textDocument/hover", params(fixture.nameRange(anchor).start))
            observe(recorder, state, "hover.$suffix") { ColdRecoveryAssertions.hover(result, fixture, anchor) }
        }
        val outline = request("textDocument/documentSymbol", params())
        for ((suffix, anchor) in listOf("before" to fixture.before, "after" to fixture.after)) {
            observe(recorder, state, "outline.$suffix") {
                if (outline is JsonArray) {
                    val names = outline.map { it.jsonObject.getValue("name").jsonPrimitive.content }
                    assertTrue(names.none { it in oldNames }, "Stale warm outline identity")
                }
                ColdRecoveryAssertions.outline(outline, fixture, anchor)
            }
        }
        val position = fixture.completionPosition
        if (state.prefix) position.character++
        val completion = request("textDocument/completion", params(position))
        observe(recorder, state, "completion.enum") {
            WarmRecoveryAssertions.completion(
                completion,
                state.marker,
                previous.map { it.marker }.toSet(),
                prefix = state.prefix,
            )
        }
    }

    private fun observe(recorder: NamedObservationRecorder, state: State, id: String, check: () -> Unit) {
        val checkId = "${state.label}.$id"
        val xfail = observedFailures[checkId]?.let { message ->
            Xfail(2082, "Intact declaration lost after an incomplete edit", "^${Regex.escape(message)}$")
        }
        recorder.observe(JsonCheck("$.recovery", JsonExpectation(), id = checkId, xfail = xfail), check)
    }

    private fun states(newline: String): List<State> = listOf(
        state("valid", 1, "NEW", newline),
        state("hole", 2, "", newline),
        state("prefix", 3, "N", newline),
        state("restored", 4, "PAID", newline),
    )

    private fun state(label: String, version: Int, member: String, newline: String): State {
        val marker = "NEXT_V$version"
        val fixture = ColdRecoveryFixture(
            tag = "WarmV$version",
            malformed = member.isEmpty(),
            newline = newline,
            member = member,
            extraConstant = marker,
        )
        return State(label, version, marker, fixture, prefix = member == "N")
    }

    private data class State(
        val label: String,
        val version: Int,
        val marker: String,
        val fixture: ColdRecoveryFixture,
        val prefix: Boolean,
    )

    private companion object {
        // Measured in the source-qualified a174 baseline for LF and CRLF. Later checks were blocked.
        // See https://github.com/albertocavalcante/gvy/issues/2082; remove metadata when the check passes.
        val observedFailures = mapOf(
            "hole.tokens.before" to "recovery: missing class token BeforeWarmV2",
        )
        val checks = listOf(
            "diagnostics.integrity",
            "tokens.before",
            "tokens.after",
            "hover.before",
            "hover.after",
            "outline.before",
            "outline.after",
            "completion.enum",
        )
    }
}
