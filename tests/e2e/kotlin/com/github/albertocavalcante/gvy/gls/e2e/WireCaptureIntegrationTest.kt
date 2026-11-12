// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import com.google.gson.JsonParser
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WireCaptureIntegrationTest {
    @Test
    fun `in-process factory captures actual requests errors and notifications independently of SDK results`() {
        val server = SessionTestServer()
        LanguageServerSessionFactory(createServer = { server })
            .start(ServerConfig(mode = ServerLaunchMode.InProcess), "raw-in-process").use { session ->
                session.server.initialize(InitializeParams()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertThatThrownBy {
                    session.endpoint.request("custom/missing", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.isInstanceOf(ExecutionException::class.java).hasCauseInstanceOf(ResponseErrorException::class.java)
                requireNotNull(server.connectedClient).showMessage(
                    org.eclipse.lsp4j.MessageParams().apply {
                        message = "wire-notification-control"
                        type = org.eclipse.lsp4j.MessageType.Info
                    },
                )
                session.client.awaitNotificationDetailed("window/showMessage", 5_000, { true })

                val evidence = requireNotNull(session.snapshotWireEvidence())
                assertThat(
                    evidence.transmitted.text(),
                ).contains("\"method\":\"initialize\"", "\"method\":\"custom/missing\"")
                assertThat(evidence.received.text()).contains("\"result\":", "\"error\":", "\"code\":-32601")
                    .contains("\"method\":\"window/showMessage\"", "wire-notification-control")
                assertThat(evidence.received.gaps).isEmpty()
                assertThat(evidence.transmitted.gaps).isEmpty()
            }
    }

    @Test
    fun `typed SDK decoding cannot replace the original response envelope`() {
        val capture = BoundedWireCapture()
        val requests = ByteArrayOutputStream()
        val incoming = PipedInputStream()
        val peerOutput = PipedOutputStream(incoming)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val launcher = Launcher.Builder<LanguageServer>()
                .setLocalService(HarnessLanguageClient())
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(capture.input(incoming))
                .setOutput(capture.output(requests))
                .setExecutorService(executor)
                .create()
            // Request registration precedes reading this intentionally richer peer response.
            val pending = launcher.remoteProxy.initialize(InitializeParams())
            val request = JsonParser.parseString(requests.toString(Charsets.UTF_8).substringAfter("\r\n\r\n"))
                .asJsonObject
            val response = """{"jsonrpc":"2.0","id":${request.get("id")},"result":{"capabilities":{},"left":null}}"""
            val original = frame(response).toByteArray()
            peerOutput.write(original)
            peerOutput.close()
            val listening = launcher.startListening()
            val decoded = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            listening.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertThat(JsonBridge.toJsonElement(decoded).jsonObject).doesNotContainKey("left")
            assertThat(Base64.getDecoder().decode(capture.snapshot().received.prefixBase64)).isEqualTo(original)
            assertThat(capture.snapshot().received.text()).contains("\"left\":null")
            assertThat(capture.snapshot().received.losslessSoFar).isTrue()
            assertThat(capture.snapshot().received.endOfInputObserved).isTrue()
        } finally {
            peerOutput.close()
            incoming.close()
            executor.shutdownNow()
            check(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `subprocess factory hooks retain original peer bytes and completed client writes`() {
        val body = """{"jsonrpc":"2.0","method":"window/showMessage","params":{""" +
            """"type":3,"message":"process-byte-control"}}"""
        val original = frame(body).toByteArray()
        val process = StreamProcess(original)
        val factory = LanguageServerSessionFactory(startProcess = { process })
        // This is a process-adapter control, not proof that an external candidate artifact was executed.
        factory.start(
            ServerConfig(mode = ServerLaunchMode.Stdio, env = mapOf("GRADLE_USER_HOME" to "unused-by-fake-process")),
            "raw-process-adapter",
        ).use { session ->
            session.client.awaitNotificationDetailed("window/showMessage", 5_000, { true })
            session.endpoint.notify("custom/outgoing", mapOf("left" to listOf(1, 2)))
            val evidence = requireNotNull(session.snapshotWireEvidence())
            assertThat(Base64.getDecoder().decode(evidence.received.prefixBase64)).isEqualTo(original)
            assertThat(Base64.getDecoder().decode(evidence.transmitted.prefixBase64))
                .isEqualTo(process.sent.toByteArray())
            assertThat(evidence.transmitted.text()).contains("\"method\":\"custom/outgoing\"", "\"left\":[1,2]")
            assertThat(evidence.received.gaps).isEmpty()
            assertThat(evidence.transmitted.gaps).isEmpty()
        }
    }

    private class StreamProcess(bytes: ByteArray) : Process() {
        private val received = ByteArrayInputStream(bytes)
        val sent = ByteArrayOutputStream()

        override fun getOutputStream(): ByteArrayOutputStream = sent
        override fun getInputStream(): ByteArrayInputStream = received
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = false
        override fun destroy() = Unit
    }

    private fun WireStreamEvidence.text(): String = Base64.getDecoder().decode(prefixBase64).toString(Charsets.UTF_8)
    private fun frame(body: String): String = "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
