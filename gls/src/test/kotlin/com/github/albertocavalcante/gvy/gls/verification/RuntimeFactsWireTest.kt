// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.verification

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.google.gson.JsonParser
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeFactsWireTest {
    @Test
    fun `request respects initialization and shutdown without creating the hover engine`() {
        val server = GroovyLanguageServer(exitProcess = {})
        val before = assertFailsWith<ExecutionException> { server.runtimeFacts().get() }.cause as ResponseErrorException
        assertEquals(ResponseErrorCode.ServerNotInitialized.value, before.responseError.code)
        server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
        val facts = server.runtimeFacts().join().asJsonObject
        assertEquals(1, facts.get("schemaVersion").asInt)
        assertEquals("not_instantiated", facts.getAsJsonObject("hoverEngine").get("state").asString)
        assertFalse(facts.getAsJsonObject("hoverEngine").has("instantiated"))
        val shutdown = server.shutdown()
        val after = assertFailsWith<ExecutionException> { server.runtimeFacts().get() }.cause as ResponseErrorException
        assertEquals(ResponseErrorCode.InvalidRequest.value, after.responseError.code)
        shutdown.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `actual server codec omits unavailable values without unrelated metadata`() {
        val server = GroovyLanguageServer(exitProcess = {})
        server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
        val body = """{"jsonrpc":"2.0","id":1,"method":"groovy/runtimeFacts"}"""
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
            val hover = result.getAsJsonObject("hoverEngine")
            assertEquals("not_instantiated", hover.get("state").asString)
            assertFalse(hover.has("instantiated"))
            val runtime = result.getAsJsonObject("runtime")
            assertEquals("known", runtime.get("processState").asString)
            assertTrue(runtime.has("processId"))
            assertFalse(runtime.get("processId").isJsonNull)
            assertFalse(result.has("environment"))
            assertFalse(result.has("arguments"))
            assertFalse(result.getAsJsonObject("build").has("branch"))
            assertFalse(result.getAsJsonObject("build").has("ciRunUrl"))
        } finally {
            executor.shutdownNow()
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }
}
