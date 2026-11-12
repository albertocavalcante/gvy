// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Every request must answer on a class hierarchy cycle instead of hanging the server (issue #1369). */
class InheritanceCycleServerTest {
    private var serverHandle: TestLanguageServerHandle? = null
    private val uri = "file:///tmp/cycle-test/Probe.groovy"
    private val source = "class A extends B { def m() { foo.bar(); new A().zz } }\nclass B extends A {}\n"
    private val doc get() = serverHandle!!.server.textDocumentService

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
        // didOpen is a notification: it returns before indexing, so quiescence is awaited under the same bound
        doc.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, source)))
        handle.client.awaitQuiescentStatus()
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    private fun id() = TextDocumentIdentifier(uri)

    @Test
    fun `hover on a missing property answers`() {
        doc.hover(HoverParams(id(), Position(0, source.indexOf("zz")))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `hover on a class name answers`() {
        doc.hover(HoverParams(id(), Position(0, 6))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `completion answers`() {
        doc.completion(CompletionParams(id(), Position(0, source.indexOf("zz")))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `document symbols, semantic tokens and folding answer`() {
        doc.documentSymbol(DocumentSymbolParams(id())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        doc.semanticTokensFull(SemanticTokensParams(id())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        doc.foldingRange(FoldingRangeRequestParams(id())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
