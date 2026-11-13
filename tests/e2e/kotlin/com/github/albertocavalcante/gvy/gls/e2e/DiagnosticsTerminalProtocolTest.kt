// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.github.albertocavalcante.gvy.gls.diagnostics.AwaitDiagnosticsParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Isolated("In-process initialization changes process logging levels")
@Execution(ExecutionMode.SAME_THREAD)
class DiagnosticsTerminalProtocolTest {
    @TempDir
    lateinit var workspace: Path

    @TestFactory
    fun `terminal diagnostics have the same contract over both transports`(): List<DynamicTest> =
        listOf(ServerLaunchMode.InProcess, ServerLaunchMode.Stdio).map { mode ->
            dynamicTest(mode.name) { verifyTransport(mode) }
        }

    @TestFactory
    fun `wire versions cannot be coerced to a different integer`(): List<DynamicTest> =
        listOf(ServerLaunchMode.InProcess, ServerLaunchMode.Stdio).flatMap { mode ->
            listOf(
                "quoted integer" to JsonPrimitive("1"),
                "nonintegral decimal" to JsonPrimitive(BigDecimal("1.0000000000000000000001")),
            ).map { (label, version) ->
                dynamicTest("${mode.name}: $label") { verifyTransport(mode, version) }
            }
        }

    @Test
    fun `in-process terminal session restores process logging levels`() {
        val original = processLogLevels()
        try {
            val context = assertIs<LoggerContext>(LoggerFactory.getILoggerFactory())
            context.getLogger(Logger.ROOT_LOGGER_NAME).level = Level.WARN
            context.getLogger("com.github.albertocavalcante.groovylsp").level = Level.DEBUG
            context.getLogger("GroovyLSP").level = null
            val expected = processLogLevels()

            verifyTransport(ServerLaunchMode.InProcess)

            assertTrue(expected == processLogLevels(), "In-process terminal session leaked process log levels")
        } finally {
            restoreProcessLogLevels(original)
        }
    }

    private fun verifyTransport(mode: ServerLaunchMode, invalidVersion: JsonElement? = null) {
        val saved = processLogLevels()
        try {
            verifyTransportSession(mode, invalidVersion)
        } finally {
            restoreProcessLogLevels(saved)
        }
    }

    private fun processLogLevels(): Map<Logger, Level?> {
        val context = assertIs<LoggerContext>(LoggerFactory.getILoggerFactory())
        return PROCESS_LOGGERS.associate { name ->
            val logger = context.getLogger(name)
            logger to logger.level
        }
    }

    private fun restoreProcessLogLevels(saved: Map<Logger, Level?>) {
        saved.forEach { (logger, level) -> logger.level = level }
    }

    private fun verifyTransportSession(mode: ServerLaunchMode, invalidVersion: JsonElement?) {
        val uri = workspace.resolve("Example.groovy").toUri().toString()
        LanguageServerSessionFactory().start(ServerConfig(mode = mode), "terminal-${mode.name}").use { session ->
            val initialization = InitializeParams().apply {
                processId = ProcessHandle.current().pid().toInt()
                capabilities = ClientCapabilities()
                workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "terminal-control"))
            }
            session.server.initialize(initialization).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.server.initialized(InitializedParams())
            session.client.readyFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, SOURCE)),
            )

            val first = terminal(session, parameters(uri, JsonPrimitive(1)))
            assertEquals(uri, first.getValue("uri").jsonPrimitive.content)
            assertEquals(1, first.getValue("version").jsonPrimitive.int)
            assertTrue(first.getValue("complete").jsonPrimitive.boolean)
            assertTrue(first.getValue("runId").jsonPrimitive.long > 0)
            assertTrue(first.getValue("configurationGeneration").jsonPrimitive.long >= 0)
            assertTrue(first.getValue("failures").jsonArray.isEmpty())
            assertTrue(JsonPrimitive("custom-rules") in first.getValue("providerIds").jsonArray)
            assertIs<kotlinx.serialization.json.JsonArray>(first.getValue("diagnostics"))
            val typed = session.groovyServer.awaitDiagnostics(AwaitDiagnosticsParams(uri, 1))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(first, JsonBridge.wrapJavaObject(typed), "Typed client calls retain the same terminal result")
            val repeated = terminal(session, parameters(uri))
            assertEquals(first, repeated, "A completed run remains observable without starting another analysis")
            invalidVersion?.let {
                assertRpcError(session, parameters(uri, it), ResponseErrorCode.InvalidParams)
            }

            assertRpcError(session, parameters(uri, JsonPrimitive(2)), ResponseErrorCode.ContentModified)
            assertRpcError(session, parameters(""), ResponseErrorCode.InvalidParams)
            session.server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            assertRpcError(session, parameters(uri, JsonPrimitive(1)), ResponseErrorCode.ContentModified)
            session.server.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.exit()
        }
    }

    private fun terminal(session: LanguageServerSession, params: JsonElement): JsonObject =
        assertIs<JsonObject>(JsonBridge.wrapJavaObject(request(session, params)), "The client must decode the result")

    private fun assertRpcError(session: LanguageServerSession, params: JsonElement, code: ResponseErrorCode) {
        val failure = assertFailsWith<ExecutionException> { request(session, params) }
        val rpc = assertIs<ResponseErrorException>(failure.cause)
        assertEquals(code.value, rpc.responseError.code)
    }

    private fun request(session: LanguageServerSession, params: JsonElement): Any? {
        val future = session.endpoint.request("groovy/awaitDiagnostics", RawJsonRpcParams(params))
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    private fun parameters(uri: String, version: JsonElement? = null): JsonObject = buildJsonObject {
        put("uri", uri)
        version?.let { put("version", it) }
    }

    private companion object {
        // These are the exact process-wide writers in LogLevelConfigurator.apply.
        val PROCESS_LOGGERS = listOf(
            Logger.ROOT_LOGGER_NAME,
            "com.github.albertocavalcante.groovylsp",
            "GroovyLSP",
        )
        const val TIMEOUT_SECONDS = 30L
        const val SOURCE = "class Example {\n    int value = 1\n}\n"
    }
}
