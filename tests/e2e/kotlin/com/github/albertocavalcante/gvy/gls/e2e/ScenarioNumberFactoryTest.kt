// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
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

class ScenarioNumberFactoryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `the actual client factory retains untyped response numbers and typed initialization`() {
        val lifecycle = SessionTestServer()
        val expected = """
            {"integer":123456789012345678901234567890,
             "fraction":0.123456789012345678901234567890,"ordinary":7,"longValue":2147483648}
        """.trimIndent()
        val server = NumberResponseServer(lifecycle, expected)
        val factory = LanguageServerSessionFactory(createServer = { onExit ->
            lifecycle.onExit = onExit
            server
        })

        factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "exact-number-response").use { session ->
            val initialized = session.server.initialize(InitializeParams())
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertThat(initialized.capabilities.textDocumentSync.left).isEqualTo(TextDocumentSyncKind.Incremental)
            val context = ScenarioContext(
                definition = ScenarioDefinition(Scenario(name = "number-response", steps = emptyList())),
                session = session,
                workspace = ScenarioWorkspace(root),
                jsonPathConfig = Configuration.builder().jsonProvider(JsonSmartJsonProvider())
                    .mappingProvider(JsonSmartMappingProvider()).build(),
            )

            SendRequestStepExecutor().execute(
                ScenarioStep.SendRequest(
                    "workspace/executeCommand",
                    Json.parseToJsonElement("""{"command":"numbers"}"""),
                ),
                context,
            )

            assertThat(context.lastResult).isEqualTo(Json.parseToJsonElement(expected))
        }
        assertThat(lifecycle.shutdownCalls).isEqualTo(1)
    }

    @Test
    fun `the pinned Gson number strategy leaves typed SDK and ordinary Int Long fields unchanged`() {
        val gson = GsonBuilder().preserveUntypedJsonNumbers().create()

        assertThat(gson.fromJson("""{"line":1,"character":2}""", Position::class.java)).isEqualTo(Position(1, 2))
        val typed = gson.fromJson("""{"small":7,"large":9007199254740993}""", TypedNumbers::class.java)
        assertThat(typed.small.toString()).isEqualTo("7")
        assertThat(typed.large.toString()).isEqualTo("9007199254740993")
    }

    @Test
    fun `loaded initialization options retain precise numbers through the typed client route`() {
        val definition = JacksonScenarioParser().parseScenarioDefinition(
            """
            name: exact-initialization-options
            steps:
              - initialize:
                  initializationOptions:
                    integer: 123456789012345678901234567890
                    fraction: 0.123456789012345678901234567890
            """.trimIndent(),
        )
        val expected = Json.parseToJsonElement(
            """
            {"integer":123456789012345678901234567890,
             "fraction":0.123456789012345678901234567890}
            """.trimIndent(),
        )
        val lifecycle = SessionTestServer()
        val server = NumberResponseServer(lifecycle, "true")
        val factory = LanguageServerSessionFactory(createServer = { onExit ->
            lifecycle.onExit = onExit
            server
        })
        factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "exact-initialization-options").use { session ->
            val context = ScenarioContext(
                definition = definition,
                session = session,
                workspace = ScenarioWorkspace(root),
                jsonPathConfig = Configuration.builder().jsonProvider(JsonSmartJsonProvider())
                    .mappingProvider(JsonSmartMappingProvider()).build(),
            )

            InitializeStepExecutor().execute(definition.scenario.steps.single() as ScenarioStep.Initialize, context)

            assertThat(server.initializationOptions.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(expected)
            assertThat(context.variables["client.initializationOptions"]).isEqualTo(expected)
            assertThat(context.state.initializedResult?.capabilities?.textDocumentSync?.left)
                .isEqualTo(TextDocumentSyncKind.Incremental)
        }
    }

    private data class TypedNumbers(val small: Int, val large: Long)

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}

private class NumberResponseServer(private val lifecycle: SessionTestServer, private val response: String) :
    LanguageServer,
    LanguageClientAware {
    val initializationOptions = CompletableFuture<JsonElement>()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        initializationOptions.complete(JsonBridge.wrapJavaObject(params.initializationOptions))
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental)
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }
    override fun shutdown(): CompletableFuture<Any> = lifecycle.shutdown()
    override fun exit() = lifecycle.exit()
    override fun getTextDocumentService(): TextDocumentService = lifecycle.textDocumentService
    override fun connect(client: LanguageClient) = lifecycle.connect(client)
    override fun getWorkspaceService(): WorkspaceService = object : WorkspaceService {
        override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> =
            CompletableFuture.completedFuture(JsonParser.parseString(response))
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit
        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
    }
}
