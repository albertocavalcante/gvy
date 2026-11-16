// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
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
        RecoveryReplay.run(name, "ColdIncompleteBufferRecoveryTest.kt", planned) { recorder, evidence ->
            execute(name, fixture, features, recorder, evidence)
        }
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
            val launch = RecoveryReplay.launch()
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
                    val initialized = RecoveryReplay.initialize(session, workspace, launch, evidence)
                    val legend = checkNotNull(initialized.capabilities.semanticTokensProvider).legend
                    val encoding = initialized.capabilities.positionEncoding
                    recorder.executionStarted()
                    session.server.textDocumentService.didOpen(
                        DidOpenTextDocumentParams(TextDocumentItem(source.uri, "groovy", source.version, source.text)),
                    )
                    fun request(method: String, params: JsonObject): JsonElement {
                        val result = SdkResponseProjection.unwrappedValue(
                            RecoveryReplay.await(session.endpoint.request(method, RawJsonRpcParams(params))),
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
                    RecoveryReplay.await(session.server.shutdown())
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
    }
}

internal fun resolveColdRecoveryArtifact(configuredPath: String): Path =
    checkNotNull(BazelRunfiles.resolve(configuredPath)).toRealPath()
