package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.InitializeParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class EffectiveConfigurationTest {
    @TempDir
    lateinit var workspace: Path

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
            val endpoint = server.textDocumentService as GroovyTextDocumentService

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
            val endpoint = server.textDocumentService as GroovyTextDocumentService
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
            val endpoint = server.textDocumentService as GroovyTextDocumentService

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
