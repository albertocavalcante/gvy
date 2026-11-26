// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionImportFreshControlTest {
    @Test
    fun `fresh URI offers Widget at an unfinished generic expression`(): Unit = runBlocking {
        val line = "List<Wid"
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val candidate = URI.create("file:///Widget.groovy")
            val uri = URI.create("file:///CompletionImports.groovy")
            val classSource = "package example\nclass Widget {}\n"
            compilation.onDocumentOpened(candidate)
            documents.put(candidate, classSource, DocumentVersion.of(1))
            assertTrue(compilation.compile(candidate, classSource).isSuccess)
            assertTrue(
                compilation.symbolIndexingService.findClasses(ClassNameQuery.Prefix("Wid")).any {
                    it.fullyQualifiedName ==
                        "example.Widget"
                },
            )
            compilation.onDocumentOpened(uri)
            documents.put(uri, "package demo\nimport java.util.List\n$line\n", DocumentVersion.of(1))
            assertNull(compilation.getCompiledSnapshot(uri))
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            val answer = service.completion(
                CompletionParams(TextDocumentIdentifier(uri.toString()), Position(2, "List<Wid".length)),
            ).await()
            val items = if (answer.isLeft) answer.left else answer.right.items
            val item = assertNotNull(
                items.singleOrNull {
                    it.detail == "example.Widget" &&
                        !it.additionalTextEdits.isNullOrEmpty()
                },
                "expected Widget at $line, found ${items.map { it.label to it.detail }}",
            )
            val edit = item.additionalTextEdits.single()
            assertEquals("import example.Widget\n", edit.newText)
            assertEquals(Position(2, 0), edit.range.start)
            assertEquals(edit.range.start, edit.range.end)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
