// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.gvy.gls.transport.lsp4j.registerStatusNotificationAdapter
import com.google.gson.JsonParser
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutdownWireTest {
    @Test
    fun `repeated shutdown responses have null results and preserve request IDs`() {
        val server = GroovyLanguageServer(exitProcess = {})
        val clientOutput = PipedOutputStream()
        val serverInput = PipedInputStream(clientOutput)
        val serverOutput = PipedOutputStream()
        val clientInput = BufferedInputStream(PipedInputStream(serverOutput))
        val executor = Executors.newCachedThreadPool()
        try {
            val launcher = Launcher.Builder<GroovyLanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(GroovyLanguageClient::class.java)
                .setInput(serverInput)
                .setOutput(serverOutput)
                .setExecutorService(executor)
                .configureGson(::registerStatusNotificationAdapter)
                .create()
            server.connect(launcher.remoteProxy)
            val listening = launcher.startListening()
            for (id in listOf("42", "\"shutdown-again\"")) {
                val request = """{"jsonrpc":"2.0","id":$id,"method":"shutdown"}"""
                val bytes = request.toByteArray(Charsets.UTF_8)
                clientOutput.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                clientOutput.write(bytes)
                clientOutput.flush()

                val response = executor.submit<String> { readResponse(clientInput) }.get(30, TimeUnit.SECONDS)
                val json = JsonParser.parseString(response).asJsonObject
                assertEquals(JsonParser.parseString(id), json.get("id"))
                assertTrue(json.has("result"), "Response must contain a result: $response")
                assertTrue(json.get("result").isJsonNull, "Shutdown result must be null: $response")
            }
            listening.cancel(true)
        } finally {
            try {
                closeStreams(clientOutput, clientInput)
            } finally {
                stopExecutorAndServer(executor, server)
            }
        }
    }

    private fun readResponse(input: BufferedInputStream): String {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            check(header.length < 16 * 1024) { "Response header exceeds limit" }
            val next = input.read()
            check(next >= 0) { "Unexpected end of response header" }
            header.append(next.toChar())
        }
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.toInt()
            ?: error("Missing Content-Length in response")
        check(length in 0..1024 * 1024) { "Response body exceeds limit: $length" }
        val body = input.readNBytes(length)
        check(body.size == length) { "Incomplete response body" }
        return body.toString(Charsets.UTF_8)
    }

    private fun closeStreams(output: PipedOutputStream, input: BufferedInputStream) {
        try {
            output.close()
        } finally {
            input.close()
        }
    }

    private fun stopExecutorAndServer(executor: ExecutorService, server: GroovyLanguageServer) {
        try {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Launcher executor did not stop")
        } finally {
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }
}
