// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeQueryOwnershipTest {
    @Test
    fun `current recovery preserves a local type location and constructor label`(@TempDir root: Path) = runBlocking {
        val uri = root.resolve("Use.groovy").toUri()
        val text = "class Local { Local(int number) {} }\nclass Missing extends missing.Dependency {}\n" +
            "Local f = new Local(2); println f"
        val docs = DocumentProvider().apply { put(uri, text) }
        val compilation = GroovyCompilationService(documentProvider = docs)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val held = assertIs<SnapshotAcquisition.Partial>(compilation.acquireEditorSnapshot(uri))
            assertTrue(held.snapshot.parseResult.outcome.isDegraded)
            val service = GroovyTextDocumentService(scope, compilation)
            val input = TextDocumentIdentifier(uri.toString())
            val last = text.lineSequence().last()
            val locations = service.typeDefinition(TypeDefinitionParams(input, Position(2, last.lastIndex)))
                .get(15, TimeUnit.SECONDS).left
            assertEquals(listOf(uri.toString()), locations.map { it.uri })
            assertEquals(
                Range(Position(0, 0), Position(0, text.lineSequence().first().length)),
                locations.single().range,
            )
            val hints = service.inlayHint(InlayHintParams(input, Range(Position(2, 0), Position(2, last.length))))
                .get(15, TimeUnit.SECONDS)
            assertTrue(hints.any { it.position == Position(2, last.indexOf('2')) && it.label.left == "number:" })
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `two routes share the same worker when one waiter cancels`(@TempDir root: Path) {
        val uri = root.resolve("Use.groovy").toUri()
        val text = "class Local { Local(int count) {} }\nnew Local(1)"
        val docs = DocumentProvider().apply { put(uri, text) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<ParseRequest>()
        val compilation = GroovyCompilationService(
            documentProvider = docs,
            workerSessionDecorator = { worker ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        requests += request
                        started.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                        return worker.parse(request)
                    }
                }
            },
        )
        // Each future enrolls synchronously up to its first suspension on the shared worker.
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(scope, compilation)
            val input = TextDocumentIdentifier(uri.toString())
            val first = service.typeDefinition(TypeDefinitionParams(input, Position(1, 5)))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val second = service.inlayHint(InlayHintParams(input, Range(Position(1, 0), Position(1, 12))))
            assertTrue(first.cancel(true))
            release.countDown()
            assertEquals(listOf("count:"), second.get(15, TimeUnit.SECONDS).map { it.label.left })
            val request = requests.single()
            assertEquals(uri, request.uri)
            assertEquals(ParseMode.WORKSPACE, request.parseMode)
            assertEquals(Phases.CANONICALIZATION, request.compilePhase)
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `caller qualification checks the peer actually used for inlay inference`(@TempDir root: Path) = runBlocking {
        val peerText = "class Peer { String work() { 'ok' } }"
        val text = "class Client { def use(Peer peer) { def result = peer.work(); result } }"
        val peer = Files.writeString(root.resolve("Peer.groovy"), peerText).toUri()
        val uri = Files.writeString(root.resolve("Client.groovy"), text).toUri()
        val docs = DocumentProvider().apply {
            put(peer, peerText)
            put(uri, text)
        }
        val manager = spyk(WorkspaceManager())
        val compilation = GroovyCompilationService(documentProvider = docs, workspaceManager = manager)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            manager.initializeWorkspace(root)
            compilation.compile(peer, peerText)
            val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
            val original = assertNotNull(index.getDocument(peer))
            val consulted = AtomicBoolean()
            val armed = AtomicBoolean()
            val replaced = AtomicBoolean()
            every { manager.compileContextFor(any(), any(), any()) } answers {
                val context = callOriginal()
                when (firstArg<URI>()) {
                    peer -> consulted.set(true)
                    uri -> if (armed.get() && consulted.get() && replaced.compareAndSet(false, true)) {
                        index.updateDocument(peer, original.copy(), peerText)
                    }
                }
                context
            }
            val service = GroovyTextDocumentService(scope, compilation)
            val params =
                InlayHintParams(TextDocumentIdentifier(uri.toString()), Range(Position(0, 0), Position(0, text.length)))
            assertTrue(service.inlayHint(params).get(15, TimeUnit.SECONDS).any { it.label.left == ": String" })
            assertTrue(consulted.get(), "the semantic resolver must have qualified the actual peer")
            consulted.set(false)
            armed.set(true)
            val failure = assertThrows<ExecutionException> { service.inlayHint(params).get(15, TimeUnit.SECONDS) }
            assertTrue(replaced.get(), "post-computation caller validation must follow the peer read")
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(failure.cause).responseError.code,
            )
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
