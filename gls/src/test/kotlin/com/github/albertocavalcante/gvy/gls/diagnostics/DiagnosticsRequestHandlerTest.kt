// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsRequestHandlerTest {
    private val uri = URI.create("file:///workspace/Example.groovy")
    private val params = parameters("7")
    private val result = DiagnosticsRunResult(
        uri = uri.toString(),
        version = 7,
        runId = 12,
        configurationGeneration = 3,
        diagnostics = listOf(Diagnostic().apply { message = "retained result" }),
        providerIds = listOf("healthy", "failed"),
        failures = listOf(DiagnosticProviderFailure("failed", IOException::class.java.name, "rules unavailable")),
    )

    private fun withRequestScope(block: suspend CoroutineScope.() -> Unit): Unit =
        runBlocking { supervisorScope(block) }

    @TestFactory
    fun `invalid requests fail before querying analysis`(): List<DynamicTest> = listOf(
        null,
        "null",
        "[]",
        "true",
        "1",
        "\"parameters\"",
        "{}",
        "{\"uri\":null}",
        "{\"uri\":true}",
        "{\"uri\":1}",
        "{\"uri\":[]}",
        "{\"uri\":{}}",
        "{\"uri\":\"\"}",
        "{\"uri\":\"  \"}",
        "{\"uri\":\"Example.groovy\"}",
        "{\"uri\":\"file:///bad space\"}",
        "{\"uri\":\"1file:///Example.groovy\"}",
    ).map { input ->
        dynamicTest("invalid request: ${input ?: "missing parameters"}") {
            assertInvalidRequest(input?.let(JsonParser::parseString))
        }
    }

    @TestFactory
    fun `invalid version shapes and inexact numbers fail before querying analysis`(): List<DynamicTest> = listOf(
        "\"7\"",
        "true",
        "[]",
        "{}",
        "2147483648",
        "-2147483649",
        "1.0000000000000000000001",
        "1e999999999",
        "1e-999999999",
    ).map { version ->
        dynamicTest("invalid version: $version") { assertInvalidRequest(parameters(version)) }
    }

    @TestFactory
    fun `oversized version numbers fail before querying analysis`(): List<DynamicTest> = listOf(
        "long zero coefficient" to "0.${"0".repeat(510)}",
        "long nonzero coefficient" to "9".repeat(512),
        "long fractional coefficient" to "0.${"0".repeat(509)}1",
        "long exponent" to "1e${"0".repeat(510)}",
        "65 character exact integer" to "7.${"0".repeat(63)}",
    ).map { (label, version) ->
        dynamicTest(label) {
            val request = parameters(version)
            assertTrue(
                request.get("version").asJsonPrimitive.isNumber,
                "The size-limit control must retain a JSON number",
            )
            assertInvalidRequest(request)
        }
    }

    @TestFactory
    fun `optional and exact signed integer versions retain their values`(): List<DynamicTest> =
        listOf<Pair<String?, Int?>>(
            null to null,
            "null" to null,
            "-2147483648" to Int.MIN_VALUE,
            "-1" to -1,
            "0" to 0,
            "2147483647" to Int.MAX_VALUE,
            "7.0" to 7,
            "7e0" to 7,
            "7.${"0".repeat(62)}" to 7,
            "7e${"0".repeat(62)}" to 7,
            "0e999999999" to 0,
        ).map { (version, expected) ->
            dynamicTest("accepted version: ${version ?: "omitted version"}") {
                withRequestScope {
                    val observed = mutableListOf<Pair<URI, Int?>>()
                    val handler =
                        DiagnosticsRequestHandler(CoroutineScope(currentCoroutineContext())) { document, value ->
                            observed += document to value
                            result
                        }

                    assertSame(result, handler.awaitDiagnostics(parameters(version)).await())

                    assertEquals(listOf(uri to expected), observed)
                }
            }
        }

    private fun assertInvalidRequest(params: JsonElement?): Unit = withRequestScope {
        var invoked = false
        val handler = DiagnosticsRequestHandler(CoroutineScope(currentCoroutineContext())) { _, _ ->
            invoked = true
            result
        }

        val failure = assertFailsWith<ResponseErrorException> { handler.awaitDiagnostics(params).await() }

        assertEquals(ResponseErrorCode.InvalidParams.value, failure.responseError.code)
        assertFalse(invoked)
    }

    private fun parameters(version: String?): JsonObject = JsonObject().apply {
        addProperty("uri", uri.toString())
        version?.let { add("version", JsonParser.parseString(it)) }
    }

    @Test
    fun `completed analysis retains identity version failures and cohort on repeated queries`(): Unit =
        withRequestScope {
            val observed = mutableListOf<Pair<URI, Int?>>()
            val handler = DiagnosticsRequestHandler(CoroutineScope(currentCoroutineContext())) { document, version ->
                observed += document to version
                result
            }

            repeat(2) { assertSame(result, handler.awaitDiagnostics(params).await()) }

            assertEquals(listOf<Pair<URI, Int?>>(uri to 7, uri to 7), observed)
            assertTrue(result.complete)
            assertEquals(1, result.failures.size)
        }

    @Test
    fun `a request waits for terminal provider completion`(): Unit = runTest {
        val terminal = CompletableDeferred<DiagnosticsRunResult>()
        val handler = DiagnosticsRequestHandler(this) { _, _ -> terminal.await() }
        val request = handler.awaitDiagnostics(params)
        runCurrent()

        assertFalse(request.isDone)
        terminal.complete(result)

        assertSame(result, request.await())
    }

    @Test
    fun `cancelling one protocol waiter preserves analysis and the other waiter`(): Unit = runTest {
        val terminal = CompletableDeferred<DiagnosticsRunResult>()
        val handler = DiagnosticsRequestHandler(this) { _, _ -> terminal.await() }
        val first = handler.awaitDiagnostics(params)
        val second = handler.awaitDiagnostics(params)
        runCurrent()

        assertTrue(first.cancel(true))
        runCurrent()
        assertFalse(terminal.isCancelled)
        assertFalse(second.isDone)
        terminal.complete(result)

        assertSame(result, second.await())
        assertTrue(first.isCancelled)
    }

    @Test
    fun `unavailable or obsolete analysis returns an explicit content modified error`(): Unit = withRequestScope {
        val unavailable = DiagnosticsRunUnavailableException("The requested version has been retired")
        val handler = DiagnosticsRequestHandler(CoroutineScope(currentCoroutineContext())) { _, _ ->
            throw unavailable
        }

        val failure = assertFailsWith<ResponseErrorException> { handler.awaitDiagnostics(params).await() }

        assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
        assertSame(unavailable, failure.cause)
        assertTrue(failure.responseError.message.contains("retired"))
    }

    @Test
    fun `an unexpected analysis failure cannot become an empty successful response`(): Unit = withRequestScope {
        val failure = IOException("analysis unavailable")
        val handler = DiagnosticsRequestHandler(CoroutineScope(currentCoroutineContext())) { _, _ -> throw failure }

        val outcome = handler.awaitDiagnostics(params).handle { _, error -> error }.await()

        assertSame(failure, outcome)
    }
}
