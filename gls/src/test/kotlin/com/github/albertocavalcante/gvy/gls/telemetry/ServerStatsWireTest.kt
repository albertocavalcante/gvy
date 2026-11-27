// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.telemetry

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerStatsWireTest {
    @Test
    fun `request respects initialization and shutdown`() {
        val server = GroovyLanguageServer(exitProcess = {})
        val before = assertFailsWith<ExecutionException> { server.serverStats().get() }.cause as ResponseErrorException
        assertEquals(ResponseErrorCode.ServerNotInitialized.value, before.responseError.code)
        server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
        val stats = server.serverStats().join().asJsonObject
        assertEquals(1, stats.get("schemaVersion").asInt)
        val shutdown = server.shutdown()
        val after = assertFailsWith<ExecutionException> { server.serverStats().get() }.cause as ResponseErrorException
        assertEquals(ResponseErrorCode.InvalidRequest.value, after.responseError.code)
        shutdown.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `actual server codec answers with the three top level keys and a completed initialize phase`() {
        val server = GroovyLanguageServer(exitProcess = {})
        server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
        val body = """{"jsonrpc":"2.0","id":1,"method":"groovy/serverStats"}"""
        val frame = "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
        val output = ByteArrayOutputStream()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val launcher = Launcher.Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(ByteArrayInputStream(frame.toByteArray(Charsets.UTF_8)))
                .setOutput(output)
                .setExecutorService(executor)
                .create()
            launcher.startListening().get(10, TimeUnit.SECONDS)
            val wire = output.toString(Charsets.UTF_8)
            val json = JsonParser.parseString(wire.substringAfter("\r\n\r\n")).asJsonObject
            val result = json.getAsJsonObject("result")

            assertEquals(setOf("schemaVersion", "build", "startup"), result.keySet())
            assertEquals("completed", phase(result, "lsp.initialize").get("status").asString)
        } finally {
            executor.shutdownNow()
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a server without a workspace skips the five workspace phases`() {
        val server = GroovyLanguageServer(exitProcess = {})
        try {
            server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
            server.initialized(InitializedParams())

            val result = server.serverStats().join().asJsonObject

            val workspacePhases = StartupPhase.entries.filter { it != StartupPhase.LSP_INITIALIZE }
            assertEquals(5, workspacePhases.size)
            workspacePhases.forEach { phase ->
                assertEquals("skipped", phase(result, phase.wireName).get("status").asString, phase.wireName)
            }
        } finally {
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }

    private fun phase(result: JsonObject, name: String): JsonObject = result.getAsJsonObject("startup")
        .getAsJsonArray("phases")
        .map { it.asJsonObject }
        .single { it.get("name").asString == name }
}
