// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.google.gson.JsonParser
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AstWireTest {
    @Test
    fun `full text notification precedes matching AST request on wire`() {
        val server = GroovyLanguageServer(exitProcess = {})
        server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
        val messages = listOf(
            """{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{
                |"uri":"file:///Wire.groovy","languageId":"groovy","version":1,"text":"class Old {}"}}}"""
                .trimMargin().replace("\n", ""),
            """{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{
                |"uri":"file:///Wire.groovy","version":2},"contentChanges":[{"text":"class Current {}"}]}}"""
                .trimMargin().replace("\n", ""),
            """{"jsonrpc":"2.0","id":1,"method":"groovy/ast",
                |"params":{"uri":"file:///Wire.groovy","parser":"native"}}
            """.trimMargin().replace("\n", ""),
            """{"jsonrpc":"2.0","id":2,"method":"groovy/ast","params":{"parser":"native"}}""",
            """{"jsonrpc":"2.0","id":3,"method":"groovy/ast","params":{"uri":null,"parser":"native"}}""",
            """{"jsonrpc":"2.0","id":4,"method":"groovy/ast","params":{"uri":"file:///Wire.groovy"}}""",
            """{"jsonrpc":"2.0","id":5,"method":"groovy/ast",
                |"params":{"uri":"file:///Wire.groovy","parser":null}}
            """.trimMargin().replace("\n", ""),
            """{"jsonrpc":"2.0","id":6,"method":"groovy/ast",
                |"params":{"uri":"file:relative.groovy","parser":"native"}}
            """.trimMargin().replace("\n", ""),
            """{"jsonrpc":"2.0","id":7,"method":"groovy/ast","params":null}""",
            """{"jsonrpc":"2.0","id":8,"method":"groovy/ast"}""",
        )
        val input = messages.joinToString("") { body ->
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
        }
        val output = ByteArrayOutputStream()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val launcher = Launcher.Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)))
                .setOutput(output)
                .setExecutorService(executor)
                .create()
            launcher.startListening().get(10, TimeUnit.SECONDS)
            val responses = readResponses(output)
            val result = assertNotNull(responses[1]).getAsJsonObject("result")
            assertEquals(2, result.get("documentVersion").asInt)
            assertTrue(result.get("ast").asString.contains("Current"))
            for (id in 2..8) {
                val error = assertNotNull(responses[id]).getAsJsonObject("error")
                assertEquals(ResponseErrorCode.InvalidParams.value, error.get("code").asInt, "request $id")
            }
        } finally {
            executor.shutdownNow()
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }
    private fun readResponses(output: ByteArrayOutputStream): Map<Int, com.google.gson.JsonObject> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var responses = emptyMap<Int, com.google.gson.JsonObject>()
        while (responses.size < 8 && System.nanoTime() < deadline) {
            val wire = output.toString(Charsets.UTF_8)
            val bodies = wire.split(Regex("Content-Length: \\d+\\r\\n\\r\\n"))
            responses = bodies.mapNotNull { body ->
                runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            }.filter { it.has("id") && !it.get("id").isJsonNull }.associateBy { it.get("id").asInt }
            if (responses.size < 8) Thread.sleep(10)
        }
        return responses
    }
}
