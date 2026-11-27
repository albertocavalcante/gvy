// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.github.albertocavalcante.gvy.gls.SynchronizingTestLanguageClient
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionItemCapabilities
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SnippetCapabilityTest {
    @Test
    fun `reinitialization without capability resets previous snippet support`() {
        val server = GroovyLanguageServer()
        server.connect(SynchronizingTestLanguageClient())
        try {
            val supported = ClientCapabilities().apply {
                textDocument = TextDocumentClientCapabilities().apply {
                    completion = CompletionCapabilities(CompletionItemCapabilities(true))
                }
            }
            server.initialize(InitializeParams().apply { capabilities = supported }).join()
            server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
            val source = "class Greeter { String greet(String name) { name } }\nnew Greeter().gre"
            val uri = "file:///SnippetReset.groovy"
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, source)))
            val response = server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), Position(1, "new Greeter().gre".length)),
            ).join()
            val items = if (response.isLeft) response.left else response.right.items
            val greet = items.first { it.label == "greet" }
            assertEquals(InsertTextFormat.PlainText, greet.insertTextFormat)
            assertEquals("greet()", greet.insertText)
        } finally {
            server.shutdown().join()
        }
    }

    @Test
    fun `omitted snippet support selects plain method insertion`(): Unit = assertMethodInsertion(null)

    @Test
    fun `false snippet support selects plain method insertion`(): Unit = assertMethodInsertion(false)

    @Test
    fun `true snippet support selects snippet method insertion`(): Unit = assertMethodInsertion(true)

    private fun assertMethodInsertion(support: Boolean?) {
        val server = GroovyLanguageServer()
        server.connect(SynchronizingTestLanguageClient())
        try {
            val itemCapabilities = CompletionItemCapabilities(support)
            val textDocument = TextDocumentClientCapabilities()
            textDocument.completion = CompletionCapabilities(itemCapabilities)
            val capabilities = ClientCapabilities()
            capabilities.textDocument = textDocument
            server.initialize(InitializeParams().apply { this.capabilities = capabilities }).join()
            val source = "class Greeter { String greet(String name) { name } }\nnew Greeter().gre"
            val uri = "file:///SnippetCapability.groovy"
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, source)),
            )
            val response = server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), Position(1, "new Greeter().gre".length)),
            ).join()
            val items = if (response.isLeft) response.left else response.right.items
            val greet = items.first { it.label == "greet" }
            if (support == true) {
                assertEquals(InsertTextFormat.Snippet, greet.insertTextFormat)
            } else {
                assertNotEquals(InsertTextFormat.Snippet, greet.insertTextFormat)
                assertEquals("greet()", greet.insertText)
            }
        } finally {
            server.shutdown().join()
        }
    }
}
