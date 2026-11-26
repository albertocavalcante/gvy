// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class CrossFileReferenceOwnershipTest {
    enum class Change { REOPEN_ORIGIN, REOPEN_CALLER, REPLACE_INDEX_DOCUMENT }

    @ParameterizedTest
    @EnumSource(Change::class)
    fun `cross-file reference ranges need the original source and index publication`(
        change: Change,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val origin = root.resolve("A.groovy").toUri()
        val caller = root.resolve("B.groovy").toUri()
        val originText = "class A {\n    String greet() { 'hi' }\n}\n"
        val callerText = "class B {\n    String run() {\n        new A().greet()\n    }\n}\n"
        val texts = mapOf(origin to originText, caller to callerText)
        val version = DocumentVersion.of(7)
        val documents = DocumentProvider().apply {
            for ((uri, text) in texts) put(uri, text, version)
        }
        val duringLookup = AtomicReference<(() -> Unit)?>(null)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val parsed = worker.parse(request)
                    if (request.uri != origin) return parsed
                    val model = parsed.astModel
                    return parsed.copy(astModel = object : GroovyAstModel by model {
                        override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                            val node = model.getNodeAt(uri, position)
                            if (uri == origin && node != null) duringLookup.getAndSet(null)?.invoke()
                            return node
                        }
                    })
                }
            }
        })
        val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
        val service = GroovyTextDocumentService(scope, compilation, options)
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            for ((uri, text) in texts) compilation.compile(uri, text)
            val document = TextDocumentIdentifier(origin.toString())
            val params = ReferenceParams(document, Position(1, 12), ReferenceContext(false))
            val expected = listOf(Location(caller.toString(), Range(Position(2, 16), Position(2, 21))))
            assertEquals(expected, service.references(params).get(15, TimeUnit.SECONDS))
            val qualified = compilation.referenceReadiness(origin).query { view ->
                for ((uri, text) in texts) {
                    assertEquals(ParseMode.WORKSPACE, view.modeFor(uri))
                    val held = assertIs<SnapshotAcquisition.Ready>(compilation.acquireSnapshot(uri))
                    assertTrue(held.isCurrent())
                    assertEquals(text, held.snapshot.sourceText)
                    assertTrue(held.snapshot.parseResult.outcome.achievedPhase >= Phases.CANONICALIZATION)
                    assertTrue(view.matches(uri, held.snapshot))
                }
                view.index
            }
            val graph = assertIs<SnapshotQuery.Answer<CapturedWorkspaceReferences>>(qualified).value
            val indexed = assertNotNull(graph.getDocumentSnapshot(caller))
            assertEquals(callerText, indexed.sourceText)
            assertTrue(graph.isCurrent())

            if (change == Change.REPLACE_INDEX_DOCUMENT) {
                val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
                index.updateDocument(caller, indexed.document.copy(), callerText)
                val replacement = assertNotNull(index.getDocumentSnapshot(caller))
                assertNotSame(indexed.document, replacement.document)
                assertFalse(graph.isCurrent())
                assertIs<SnapshotQuery.Failure>(compilation.referenceReadiness(origin).query { it.index })
                val answer = service.references(params)
                try {
                    // A complete answer requires a genuinely repaired publication.
                    val locations = answer.get(15, TimeUnit.SECONDS)
                    assertIs<SnapshotQuery.Answer<*>>(compilation.referenceReadiness(origin).query { it.index })
                    assertEquals(expected, locations)
                } catch (failure: ExecutionException) {
                    val error = assertIs<ResponseErrorException>(failure.cause).responseError
                    assertEquals(ResponseErrorCode.RequestFailed.value, error.code)
                }
            } else {
                val reopened = if (change == Change.REOPEN_ORIGIN) origin else caller
                val before = assertNotNull(documents.getSnapshot(reopened))
                val touched = AtomicBoolean()
                duringLookup.set {
                    documents.remove(reopened)
                    documents.put(reopened, texts.getValue(reopened), version)
                    touched.set(true)
                }
                val answer = service.references(params)
                try {
                    val failure = assertThrows<ExecutionException> { answer.get(15, TimeUnit.SECONDS) }
                    val error = assertIs<ResponseErrorException>(failure.cause).responseError
                    assertEquals(ResponseErrorCode.ContentModified.value, error.code)
                } finally {
                    assertTrue(touched.get(), "the native origin lookup must reach the reincarnation barrier")
                    val after = assertNotNull(documents.getSnapshot(reopened))
                    assertNotSame(before, after)
                    assertEquals(before.content, after.content)
                    assertEquals(before.version, after.version)
                    assertSame(indexed.document, graph.getDocumentSnapshot(caller)?.document)
                }
            }
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
