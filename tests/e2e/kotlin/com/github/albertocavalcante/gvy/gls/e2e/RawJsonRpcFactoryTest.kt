// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RawJsonRpcFactoryTest {
    @TempDir
    lateinit var root: Path

    private val payload = Json.parseToJsonElement("""{"future":{"explicit":null},"flag":false}""")

    @Test
    fun `the actual client launcher preserves nested null in request arguments`() {
        val lifecycle = SessionTestServer()
        val server = RawPayloadServer(lifecycle)
        val factory = LanguageServerSessionFactory(createServer = { onExit ->
            lifecycle.onExit = onExit
            server
        })

        factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "raw-request-wire").use { session ->
            val context = context(session)
            val params = buildJsonObject {
                put("command", "capture")
                put("arguments", buildJsonArray { add(payload) })
            }

            SendRequestStepExecutor().execute(ScenarioStep.SendRequest("workspace/executeCommand", params), context)

            assertThat(server.request.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(payload)
            assertThat(context.lastResult).isEqualTo(JsonPrimitive(true))
        }
    }

    @Test
    fun `the actual client launcher preserves nested null in notification settings`() {
        val lifecycle = SessionTestServer()
        val server = RawPayloadServer(lifecycle)
        val factory = LanguageServerSessionFactory(createServer = { onExit ->
            lifecycle.onExit = onExit
            server
        })

        factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "raw-notification-wire").use { session ->
            SendNotificationStepExecutor().execute(
                ScenarioStep.SendNotification(
                    "workspace/didChangeConfiguration",
                    buildJsonObject { put("settings", payload) },
                ),
                context(session),
            )

            assertThat(server.notification.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(payload)
        }
    }

    private fun context(session: LanguageServerSession) = ScenarioContext(
        definition = ScenarioDefinition(Scenario(name = "factory-payload", steps = emptyList())),
        session = session,
        workspace = ScenarioWorkspace(root),
        jsonPathConfig = Configuration.builder()
            .jsonProvider(JsonSmartJsonProvider()).mappingProvider(JsonSmartMappingProvider()).build(),
    ).also { it.registerBuiltInVariables() }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}

private class RawPayloadServer(private val lifecycle: SessionTestServer) :
    LanguageServer,
    LanguageClientAware {
    val request = CompletableFuture<JsonElement>()
    val notification = CompletableFuture<JsonElement>()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
        lifecycle.initialize(params)
    override fun shutdown(): CompletableFuture<Any> = lifecycle.shutdown()
    override fun exit() = lifecycle.exit()
    override fun getTextDocumentService(): TextDocumentService = lifecycle.textDocumentService
    override fun connect(client: LanguageClient) = lifecycle.connect(client)

    override fun getWorkspaceService(): WorkspaceService = object : WorkspaceService {
        override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
            request.complete(JsonBridge.wrapJavaObject(params.arguments.single()))
            return CompletableFuture.completedFuture(true)
        }

        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
            notification.complete(JsonBridge.wrapJavaObject(params.settings))
        }

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
    }
}
