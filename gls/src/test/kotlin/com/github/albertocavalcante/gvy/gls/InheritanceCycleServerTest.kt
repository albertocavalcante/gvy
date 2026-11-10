package com.github.albertocavalcante.gvy.gls

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Hover and completion must answer on a class hierarchy cycle instead of hanging the server (issue #1369). */
class InheritanceCycleServerTest {
    private var serverHandle: TestLanguageServerHandle? = null
    private val uri = "file:///tmp/cycle-test/Probe.groovy"

    @BeforeEach
    fun setup() {
        val handle = TestLanguageServerRunner().startInMemoryServer()
        serverHandle = handle
        handle.server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///tmp/cycle-test", "cycle-test"))
                initializationOptions = mapOf("groovy.languageServer.engine" to "native")
            },
        ).get()
        handle.server.initialized(InitializedParams())
        handle.client.awaitQuiescentStatus()
        handle.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "groovy", 1, "class A extends B {\n  def m() { this. }\n}\nclass B extends A {}\n"),
            ),
        )
        handle.client.awaitQuiescentStatus()
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    @Test
    fun `hover answers for a cyclic hierarchy`() {
        val params = HoverParams(TextDocumentIdentifier(uri), Position(0, 6))
        serverHandle!!.server.textDocumentService.hover(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `completion answers for a cyclic hierarchy`() {
        val params = CompletionParams(TextDocumentIdentifier(uri), Position(1, 18))
        serverHandle!!.server.textDocumentService.completion(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
