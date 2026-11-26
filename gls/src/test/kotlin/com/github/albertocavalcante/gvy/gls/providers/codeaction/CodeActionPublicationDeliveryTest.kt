// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodeActionPublicationDeliveryTest {
    @Test
    fun `an import edit cannot survive retirement of the index it selected from`(): Unit = runBlocking {
        val declaration = URI.create("file:///Widget.groovy")
        val uri = URI.create("file:///UseWidget.groovy")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        try {
            val candidate = "package example\nclass Widget {}\n"
            documents.put(declaration, candidate)
            documents.put(uri, "Widget w")
            assertTrue(compilation.compile(declaration, candidate).isSuccess)
            val range = Range(Position(0, 0), Position(0, 6))
            val diagnostic = Diagnostic(range, "unable to resolve class Widget")
            val params = CodeActionParams(
                TextDocumentIdentifier(uri.toString()),
                range,
                CodeActionContext(listOf(diagnostic)),
            )
            val request = CodeActionRequest(params, compilation, documents) { false }
            // The caller may reuse or mutate its protocol object once the handler has captured it.
            diagnostic.message = "unable to resolve class Replacement"
            diagnostic.range.end.character = 2
            val fence = compilation.captureSnapshotSource(uri)
            val failure = assertFailsWith<ResponseErrorException> {
                request.deliver { document, _ ->
                    val actions = ImportAction(compilation).createImportActions(
                        uri.toString(), request.params.context.diagnostics, document.content, request::findCandidates,
                    )
                    val action = actions.single()
                    assertEquals("import example.Widget\n", action.edit.changes.getValue(uri.toString()).single().newText)
                    assertEquals(6, action.diagnostics.single().range.end.character)
                    compilation.symbolIndexingService.invalidate(declaration)
                    assertTrue(compilation.symbolIndexingService.findClasses(ClassNameQuery.Exact("Widget")).isEmpty())
                    assertTrue(fence.isCurrent(), "only the consumed index publication retired")
                    actions
                }
            }
            assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
        } finally {
            compilation.shutdown()
        }
    }
}
