// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextHandlerDeliveryTest {
    @Test
    fun `source change inside final producer qualification retires an inlay answer`(@TempDir root: Path): Unit =
        runBlocking {
            val text = "class Client { void use(String value) { value.take(2) } }"
            val uri = Files.writeString(root.resolve("Client.groovy"), text).toUri()
            val documents = DocumentProvider().apply { put(uri, text, DocumentVersion.of(1)) }
            val manager = spyk(WorkspaceManager())
            val traversed = AtomicBoolean(false)
            val armed = AtomicBoolean(false)
            val replaced = AtomicBoolean(false)
            val compilation = GroovyCompilationService(
                documentProvider = documents,
                workspaceManager = manager,
                workerSessionDecorator = { delegate ->
                    object : WorkerSession {
                        override fun parse(request: ParseRequest): ParseResult {
                            val parsed = delegate.parse(request)
                            val model = parsed.astModel
                            return parsed.copy(
                                astModel = object : GroovyAstModel by model {
                                    override fun getAllNodes(): List<ASTNode> {
                                        if (armed.get()) traversed.set(true)
                                        return model.getAllNodes()
                                    }
                                },
                            )
                        }
                    }
                },
            )
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                manager.initializeWorkspace(root)
                assertIs<SnapshotAcquisition.Ready>(compilation.acquireEditorSnapshot(uri))
                every { manager.compileContextFor(any(), any(), any()) } answers {
                    val context = callOriginal()
                    if (armed.get() && traversed.get() && replaced.compareAndSet(false, true)) {
                        documents.update(uri, text + "\n// changed", DocumentVersion.of(2))
                    }
                    context
                }
                val service = GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
                armed.set(true)
                val answer = service.inlayHint(
                    InlayHintParams(
                        TextDocumentIdentifier(uri.toString()),
                        Range(Position(0, 0), Position(0, text.length)),
                    ),
                )
                val result = runCatching { answer.get(15, TimeUnit.SECONDS) }
                assertTrue(traversed.get(), "the actual inlay traversal must execute")
                assertTrue(replaced.get(), "the final producer qualification must execute after traversal")
                assertEquals(
                    ResponseErrorCode.ContentModified.value,
                    assertIs<ResponseErrorException>(assertIs<ExecutionException>(result.exceptionOrNull()).cause)
                        .responseError.code,
                )
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }
}
