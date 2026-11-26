// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.codeaction.CodeActionRequest
import com.github.albertocavalcante.gvy.gls.providers.codeaction.ImportAction
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionInputs
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexedCandidateDeliveryTest {
    enum class Provider { IMPORT, COMPLETION }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a selected closed candidate stays current through delivery`(provider: Provider, @TempDir root: Path) =
        runBlocking {
            val documents = DocumentProvider()
            val compilation = GroovyCompilationService(documentProvider = documents)
            try {
                val file = root.resolve("Widget.groovy")
                Files.writeString(file, "package example\r\nclass Widget {}\r\n// " + "é😀".repeat(2048))
                val uri = root.resolve("Use.groovy").toUri()
                documents.put(uri, "class Use {}")
                assertNotNull(compilation.symbolIndexingService.indexFile(file.toUri()))
                assertNull(documents.getSnapshot(file.toUri()))
                suspend fun request(afterSelection: () -> Unit): String = if (provider == Provider.IMPORT) {
                    importCandidate(compilation, uri, afterSelection)
                } else {
                    completionCandidate(compilation, uri, afterSelection)
                }
                assertEquals("Widget", request {})
                val (_, publicationCurrent) = compilation.symbolIndexingService.captureRows()
                val fence = compilation.captureSnapshotSource(uri)
                var selected = false
                val failure = assertFailsWith<ResponseErrorException> {
                    request {
                        Files.writeString(file, "\r\n// saved", StandardOpenOption.APPEND)
                        selected = true
                        assertTrue(publicationCurrent() && fence.workerCurrent() && fence.isCurrent())
                    }
                }
                assertTrue(selected)
                assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
            } finally {
                compilation.shutdown()
            }
        }

    private suspend fun importCandidate(compilation: GroovyCompilationService, uri: URI, selected: () -> Unit): String {
        val start = Position(0, 0)
        val range = Range(start, Position(0, 5))
        val diagnostic = Diagnostic(range, "unable to resolve class Widget")
        val params = CodeActionParams(
            TextDocumentIdentifier(uri.toString()),
            range,
            CodeActionContext(listOf(diagnostic)),
        )
        val request = CodeActionRequest(params, compilation, compilation.documents) { false }
        val actions = request.deliver { document, held ->
            assertEquals(document.content, held.sourceText)
            val result = ImportAction(compilation).createImportActions(
                uri.toString(),
                request.params.context.diagnostics,
                document.content,
                request::findCandidates,
            )
            val action = result.single()
            assertEquals("Import 'example.Widget'", action.title)
            val edit = action.edit.changes[uri.toString()]?.single()
            assertEquals(TextEdit(Range(start, start), "import example.Widget\n"), edit)
            selected()
            result
        }
        return actions.single().title.substringAfterLast('.').removeSuffix("'")
    }

    private suspend fun completionCandidate(
        compilation: GroovyCompilationService,
        uri: URI,
        selected: () -> Unit,
    ): String {
        val request = SignatureCompletionRequest(compilation, compilation.documents, uri, Position(0, 0))
        return request.deliver {
            request.original({ error("Missing primary document") }) { native ->
                assertEquals("class Use {}", native.held.sourceText)
                val found = CompletionInputs(request, native).workspaceClasses(ClassNameQuery.Prefix("Wid")).single()
                assertEquals("example.Widget", found.fullyQualifiedName)
                assertEquals("Widget", found.name)
                selected()
                found.name
            }
        }
    }
}
