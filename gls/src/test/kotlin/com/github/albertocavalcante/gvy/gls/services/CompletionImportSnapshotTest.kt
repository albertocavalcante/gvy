// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.ImportUtils
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompletionImportSnapshotTest {
    @Test
    fun `auto import edit uses current imports instead of cached imports`(): Unit = runBlocking {
        val uri = URI.create("file:///CompletionImports.groovy")
        val candidate = URI.create("file:///Widget.groovy")
        val old = "package demo\nimport example.Widget\nclass Old {}\n"
        val current = "package demo\nimport java.util.List\nList<Wid\n"
        val documents = DocumentProvider()
        val requests = CopyOnWriteArrayList<Pair<ParseRequest, ParseResult>>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val result = delegate.parse(request)
                        requests.add(request to result)
                        return result
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val candidateSource = "package example\nclass Widget {}\n"
            compilation.onDocumentOpened(candidate)
            documents.put(candidate, candidateSource, DocumentVersion.of(1))
            val candidateResult = compilation.compile(candidate, candidateSource)
            assertTrue(candidateResult.isSuccess, "candidate must compile: ${candidateResult.diagnostics}")
            val oldResult = compilation.compile(uri, old)
            assertTrue(oldResult.isSuccess, "old import must resolve from the open candidate: ${oldResult.diagnostics}")
            val cached = assertNotNull(compilation.getCompiledSnapshot(uri), "old parse must be cached")
            assertEquals(old, cached.sourceText)
            assertEquals(Phases.CANONICALIZATION, cached.parseResult.outcome.achievedPhase)
            val oldAst = assertNotNull(cached.parseResult.ast, "cached source must have a module")
            assertTrue(
                "example.Widget" in ImportUtils.extractExistingImportsFromAst(oldAst),
                "old AST must import Widget",
            )
            val indexed = compilation.symbolIndexingService.findClasses(ClassNameQuery.Prefix("Wid"))
            assertTrue(indexed.any { it.fullyQualifiedName == "example.Widget" }, "candidate not indexed: $indexed")
            compilation.onDocumentOpened(uri)
            documents.put(uri, current, DocumentVersion.of(1))
            assertEquals(
                old,
                assertNotNull(compilation.getCompiledSnapshot(uri)).sourceText,
                "stale cache must survive open",
            )
            requests.clear()
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val answer = service.completion(
                CompletionParams(TextDocumentIdentifier(uri.toString()), Position(2, "List<Wid".length)),
            ).await()
            assertCurrentParse(requests, uri)
            val items = if (answer.isLeft) answer.left else answer.right.items
            assertImportEdit(items)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun assertCurrentParse(requests: List<Pair<ParseRequest, ParseResult>>, uri: URI) {
        val currentParses = requests.filter { (request, _) -> request.uri == uri }
        assertTrue(currentParses.isNotEmpty(), "completion must invoke a transient producer")
        assertTrue(
            currentParses.any { (request, result) ->
                request.compilePhase == Phases.CANONICALIZATION &&
                    request.content.contains("import java.util.List") && result.ast != null
            },
            "completion must parse the current imports at canonicalization with an AST",
        )
    }

    private fun assertImportEdit(items: List<CompletionItem>) {
        val item = assertNotNull(
            items.singleOrNull { it.detail == "example.Widget" && !it.additionalTextEdits.isNullOrEmpty() },
            "expected an auto-import candidate from the indexed workspace: ${items.map { it.label to it.detail }}",
        )
        val edit = item.additionalTextEdits.single()
        assertEquals("import example.Widget\n", edit.newText)
        assertEquals(Position(2, 0), edit.range.start)
        assertEquals(edit.range.start, edit.range.end)
    }
}
