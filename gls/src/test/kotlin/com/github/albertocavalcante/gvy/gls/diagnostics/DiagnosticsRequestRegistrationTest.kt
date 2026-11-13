// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.google.gson.JsonParser
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticsRequestRegistrationTest {
    @Test
    fun `the product server registers the terminal diagnostics request`() {
        val methods = ServiceEndpoints.getSupportedMethods(GroovyLanguageServer::class.java)

        assertTrue(methods.containsKey("groovy/awaitDiagnostics"))
    }

    @Test
    fun `terminal diagnostics reject requests before initialization`() {
        val server = GroovyLanguageServer(exitProcess = {})
        try {
            assertRequestError(server, ResponseErrorCode.ServerNotInitialized)
        } finally {
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `shutdown rejects terminal requests before queued cleanup begins`() {
        val pending = ArrayDeque<Runnable>()
        val server = GroovyLanguageServer(exitProcess = {}, shutdownExecutor = Executor(pending::addLast))
        server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
        val shutdown = server.shutdown()
        try {
            assertEquals(1, pending.size)
            assertFalse(shutdown.isDone)
            assertRequestError(server, ResponseErrorCode.InvalidRequest)
            assertFalse(shutdown.isDone, "The lifecycle guard must run before asynchronous cleanup")
        } finally {
            pending.removeFirst().run()
            shutdown.get(30, TimeUnit.SECONDS)
        }
    }

    private fun assertRequestError(server: GroovyLanguageServer, code: ResponseErrorCode) {
        val params = JsonParser.parseString("""{"uri":"file:///workspace/Example.groovy","version":1}""")
        val failure = assertFailsWith<ExecutionException> {
            server.awaitDiagnostics(params).get(10, TimeUnit.SECONDS)
        }
        val error = assertIs<ResponseErrorException>(failure.cause)
        assertEquals(code.value, error.responseError.code)
    }
}
