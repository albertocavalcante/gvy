// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class EffectiveConfigurationTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `JSON RPC initialization applies options at the public endpoint`(): Unit = runBlocking {
        val server = GroovyLanguageServer()
        val executor = Executors.newSingleThreadExecutor()
        val request = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "capabilities":{},"initializationOptions":{
                "groovy.diagnostics.providers.disabled":["parser","codenarc","custom-rules","unused-imports"]
              }
            }}
        """.trimIndent()
        val frame = "Content-Length: ${request.toByteArray(Charsets.UTF_8).size}\r\n\r\n$request"
        try {
            ByteArrayInputStream(frame.toByteArray(Charsets.UTF_8)).use { input ->
                ByteArrayOutputStream().use { output ->
                    val launcher = Launcher.Builder<LanguageClient>()
                        .setLocalService(server)
                        .setRemoteInterface(LanguageClient::class.java)
                        .setInput(input)
                        .setOutput(output)
                        .setExecutorService(executor)
                        .create()
                    launcher.startListening().get(10, TimeUnit.SECONDS)
                }
            }
            val endpoint = server.workspaceDocumentService
            assertTrue(endpoint.diagnose(workspace.resolve("Broken.groovy").toUri(), BROKEN).isEmpty())
        } finally {
            server.shutdown().get()
            executor.shutdownNow()
        }
    }

    @Test
    fun `JSON initialization options disable parser diagnostics at the public endpoint`(): Unit = runBlocking {
        val server = GroovyLanguageServer()
        try {
            server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    initializationOptions = JsonParser.parseString(
                        """
                        {"groovy.diagnostics.providers.disabled":["parser","codenarc","custom-rules","unused-imports"]}
                        """.trimIndent(),
                    )
                },
            ).get()
            val endpoint = server.workspaceDocumentService
            assertTrue(endpoint.diagnose(workspace.resolve("Broken.groovy").toUri(), BROKEN).isEmpty())
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `initialization options disable parser diagnostics at the public endpoint`(): Unit = runBlocking {
        val server = GroovyLanguageServer()
        try {
            server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    initializationOptions = settings(disableParser = true)
                },
            ).get()
            val endpoint = server.workspaceDocumentService

            assertTrue(endpoint.diagnose(workspace.resolve("Broken.groovy").toUri(), BROKEN).isEmpty())
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `live settings disable and reenable parser diagnostics without restart`(): Unit = runBlocking {
        val server = GroovyLanguageServer()
        try {
            server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    initializationOptions = settings(disableParser = false)
                },
            ).get()
            val endpoint = server.workspaceDocumentService
            val uri = workspace.resolve("Broken.groovy").toUri()
            assertTrue(endpoint.diagnose(uri, BROKEN).isNotEmpty(), "Control must produce a parser error")

            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings(true)))
            assertTrue(endpoint.diagnose(uri, BROKEN).isEmpty(), "Disabled providers must stop reporting")

            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings(false)))
            assertTrue(endpoint.diagnose(uri, BROKEN).isNotEmpty(), "Providers must be enabled again")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `zero problem limit applies to the public diagnose endpoint`(): Unit = runBlocking {
        val server = GroovyLanguageServer()
        try {
            server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    initializationOptions = settings(false) + ("groovy.server.maxNumberOfProblems" to 0)
                },
            ).get()
            val endpoint = server.workspaceDocumentService

            assertTrue(endpoint.diagnose(workspace.resolve("Broken.groovy").toUri(), BROKEN).isEmpty())
        } finally {
            server.shutdown().get()
        }
    }

    private fun settings(disableParser: Boolean): Map<String, Any> = mapOf(
        "groovy.diagnostics.providers.disabled" to buildList {
            addAll(listOf("codenarc", "custom-rules", "unused-imports"))
            if (disableParser) add("parser")
        },
    )

    companion object {
        private const val BROKEN = "class Broken { void method( { }"
    }
}
