// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
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
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition

class HoverCapturedMemberSourceTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `peer inference uses the available caller even after startup retry`(retry: Boolean, @TempDir root: Path) {
        runBlocking {
            Fixture(root, retry).use { fixture ->
                fixture.prepare()
                val answer = fixture.hover()
                if (retry) fixture.retireFirstProducer()
                assertContains(answer.get(15, TimeUnit.SECONDS).contents.right.value, "String")
                assertEquals(if (retry) 2 else 1, fixture.parses.get())
                assertTrue(fixture.inModel.get(), "the actual caller AST must answer the query")
                assertTrue(fixture.peerQualified.get(), "the actual peer producer must be qualified during inference")
                assertEquals(0, fixture.lateReads.get(), "peer inference reopened the caller input after AST traversal")
            }
        }
    }

    private class Fixture(root: Path, private val retry: Boolean) : AutoCloseable {
        private val callerText = "class Client { def use(Peer peer) { def result = peer.work(); result } }"
        private val peerText = "class Peer { String work() { 'ok' } }"
        private val caller = Files.writeString(root.resolve("Client.groovy"), callerText).toUri()
        private val peer = Files.writeString(root.resolve("Peer.groovy"), peerText).toUri()
        private val docs = spyk(DocumentProvider()).apply {
            put(caller, callerText)
            put(peer, peerText)
        }
        private val workspace = spyk(WorkspaceManager()).apply { initializeWorkspace(root) }
        private val firstParsed = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val parses = AtomicInteger()
        val lateReads = AtomicInteger()
        val peerQualified = AtomicBoolean()
        val inModel = AtomicBoolean()
        private val service = GroovyCompilationService(
            documentProvider = docs,
            workspaceManager = workspace,
            workerSessionDecorator = { delegate -> worker(delegate) },
        )

        suspend fun prepare() {
            service.compile(peer, peerText)
            every { docs.getBuffer(caller) } answers {
                if (inModel.get()) lateReads.incrementAndGet()
                callOriginal()
            }
            every { workspace.compileContextFor(any(), any(), any()) } answers {
                if (firstArg<URI>() == peer && inModel.get()) peerQualified.set(true)
                callOriginal()
            }
        }

        fun hover() = GroovyTextDocumentService(scope, service).hover(
            HoverParams(TextDocumentIdentifier(caller.toString()), Position(0, callerText.indexOf("result") + 1)),
        )

        suspend fun retireFirstProducer() {
            assertTrue(firstParsed.await(10, TimeUnit.SECONDS), "first real caller parse did not reach the barrier")
            service.clearCaches()
            // The replacement worker must consult the new peer publication in the new ownership window.
            service.compile(peer, peerText)
            release.countDown()
        }

        private fun worker(delegate: WorkerSession) = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                val result = delegate.parse(request)
                if (request.uri != caller) return result
                parses.incrementAndGet()
                if (retry) {
                    firstParsed.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                val model = result.astModel
                return result.copy(astModel = object : GroovyAstModel by model {
                    override fun getNodeAt(uri: URI, position: GroovyPosition): ASTNode? {
                        inModel.set(true)
                        return model.getNodeAt(uri, position)
                    }
                })
            }
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            service.shutdown()
        }
    }
}
