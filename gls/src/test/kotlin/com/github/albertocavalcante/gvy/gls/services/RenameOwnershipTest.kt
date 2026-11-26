// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class RenameOwnershipTest {
    @Test
    fun `unchanged rename edits both identifiers and prepare identifies only the use`() {
        Fixture().use { fixture ->
            fixture.assertEdits(fixture.rename().result())
            val offer = assertNotNull(fixture.prepare().result()).second
            assertEquals("total", offer.placeholder)
            assertEquals(Range(Position(1, 8), Position(1, 13)), offer.range)
            fixture.assertOriginal()
        }
    }

    @Test
    fun `queued rename uses the original uri cursor and new name`() {
        Fixture().use { fixture ->
            val params = fixture.renameParams()
            val entry = fixture.holdNextDispatch()
            val answer = fixture.service.rename(params)
            entry.await()
            params.textDocument.uri = "file:///Unrelated.groovy"
            params.position.line = -1
            params.newName = "other"
            entry.open()
            fixture.assertEdits(answer.result())
        }
    }

    @Test
    fun `prepare cannot adopt an equal text and version reopen while queued`() {
        Fixture().use { fixture ->
            val old = assertNotNull(fixture.documents.getBuffer(fixture.uri))
            val entry = fixture.holdNextDispatch()
            val answer = fixture.prepare()
            entry.await()
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.openDocument()
            val reopened = assertNotNull(fixture.documents.getBuffer(fixture.uri))
            assertEquals(old.snapshot, reopened.snapshot)
            assertFalse(old.incarnation == reopened.incarnation)
            entry.open()
            assertModified(answer)
        }
    }

    @Test
    fun `removing a completed cache entry leaves the delivered real parse usable`() {
        Fixture().use { fixture ->
            val parsed = fixture.holdParse()
            val answer = fixture.rename()
            parsed.await()
            val delivery = fixture.holdNextDispatch()
            parsed.open()
            delivery.await()
            val cache = fixture.compilation.compilationCacheService
            assertTrue(fixture.uri in cache.getCachedUris(), "worker must publish before request resumes")
            val ticket = cache.ownership.begin(fixture.uri)
            cache.invalidate(fixture.uri)
            assertTrue(fixture.uri !in cache.getCachedUris())
            assertTrue(cache.ownership.inputsCurrent(ticket), "eviction alone must leave the inputs current")
            delivery.open()
            fixture.assertEdits(answer.result())
            fixture.assertSingleParse()
        }
    }

    @Test
    fun `a worker switch during real AST lookup rejects the edit`() {
        Fixture().use { fixture ->
            val lookup = fixture.holdLookup()
            val answer = fixture.rename()
            lookup.await()
            fixture.assertSingleParse()
            assertTrue(fixture.compilation.updateSelectedWorker(replacement()))
            lookup.open()
            assertModified(answer)
            fixture.assertOriginal()
        }
    }

    @Test
    fun `a compiler classpath replacement prevents delivery of an empty prepare result`(@TempDir directory: Path) {
        Fixture("class Cases {  String call(String value) { value } }").use { fixture ->
            val first = listOf(Files.createDirectory(directory.resolve("first")))
            val second = listOf(Files.createDirectory(directory.resolve("second")))
            val workspace = fixture.compilation.workspaceManager
            assertTrue(workspace.updateDependencies(first))
            val whitespace = Position(0, fixture.source.indexOf("{  ") + 2)
            assertNull(fixture.prepare(whitespace).result(), "current whitespace must give no offer")
            val lookup = fixture.holdLookup()
            val answer = fixture.prepare(whitespace)
            lookup.await()
            assertEquals(first, fixture.parserClasspath())
            assertTrue(workspace.updateDependencies(second))
            assertEquals(second, workspace.compileContextFor(fixture.uri, fixture.source).classpath)
            lookup.open()
            assertModified(answer)
        }
    }

    @Test
    fun `a workspace source rewritten after the cross-file scan prevents the edit`(@TempDir directory: Path) {
        val origin = directory.resolve("Owned.groovy")
        val source = "class Owned {}"
        Files.writeString(origin, source)
        val companion = Files.writeString(directory.resolve("Use.groovy"), "class Use {}")
        Fixture(source, origin.toUri()).use { fixture ->
            fixture.service.updateWorkspaceFolders(listOf(directory))
            val delivery = fixture.afterWorkspaceScan()
            val answer = fixture.service.rename(
                RenameParams(TextDocumentIdentifier(fixture.uri.toString()), Position(0, 7), "Updated"),
            )
            delivery.await()
            assertEquals(1, fixture.service.renameWalks.walks)
            assertEquals("class Use {}", Files.readString(companion))
            Files.writeString(companion, "class Use { Owned value }")
            delivery.open()
            assertModified(answer)
            assertEquals(source, Files.readString(origin))
            assertFalse(Files.exists(directory.resolve("Updated.groovy")))
        }
    }

    @Test
    fun `same-name refusal is delivered only while its producer remains current`() {
        Fixture().use { fixture ->
            val current = errorFrom(fixture.rename("total"))
            assertEquals(ResponseErrorCode.InvalidRequest.value, current.responseError.code)
            assertEquals("New name is the same as the current name", current.responseError.message)
            val lookup = fixture.holdLookup()
            val answer = fixture.rename("total")
            lookup.await()
            fixture.compilation.invalidateCache(fixture.uri)
            lookup.open()
            assertModified(answer)
        }
    }

    @Test
    fun `cancelling prepare leaves compatible rename waiting for the same real worker`() {
        Fixture().use { fixture ->
            val parsed = fixture.holdParse()
            val first = fixture.prepare()
            parsed.await()
            val other = fixture.rename()
            fixture.drainRequestEntries()
            assertFalse(other.isDone)
            assertTrue(first.cancel(true))
            assertThrows<CancellationException> { first.result() }
            parsed.open()
            fixture.assertEdits(other.result())
            fixture.assertSingleParse()
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun hold() {
            entered.countDown()
            check(released.await(20, TimeUnit.SECONDS)) { "test did not release the request barrier" }
        }

        fun await() = assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach the armed barrier")

        fun open() = released.countDown()
    }

    private class Fixture(
        val source: String = "def total = 1\nprintln total\n",
        val uri: URI = URI.create("file:///RenameOwnership.groovy"),
    ) : AutoCloseable {
        val documents = DocumentProvider()
        private val allGates = CopyOnWriteArrayList<Gate>()
        private val parseGate = AtomicReference<Gate?>()
        private val lookupGate = AtomicReference<Gate?>()
        private val dispatchGate = AtomicReference<Gate?>()
        private val afterScan = AtomicReference<(() -> Unit)?>()
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute {
                    dispatchGate.getAndSet(null)?.hold()
                    afterScan.get()?.invoke()
                    block.run()
                }
            }
        }
        private val scope = CoroutineScope(dispatcher + SupervisorJob())
        private val requests = CopyOnWriteArrayList<ParseRequest>()
        private val parses = CopyOnWriteArrayList<ParseResult>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        val model = parsed.astModel
                        val decorated = parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    val node = model.getNodeAt(uri, position)
                                    lookupGate.getAndSet(null)?.hold()
                                    return node
                                }
                            },
                        )
                        requests.add(request)
                        parses.add(decorated)
                        parseGate.getAndSet(null)?.hold()
                        return decorated
                    }
                }
            },
        )
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        init {
            openDocument()
        }

        fun openDocument() {
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(41))
        }

        fun renameParams(newName: String = "sum") =
            RenameParams(TextDocumentIdentifier(uri.toString()), Position(0, 6), newName)

        fun rename(newName: String = "sum") = service.rename(renameParams(newName))

        fun prepare(position: Position = Position(1, 9)) =
            service.prepareRename(PrepareRenameParams(TextDocumentIdentifier(uri.toString()), position))

        fun holdLookup() = arm(lookupGate)

        fun holdParse() = arm(parseGate)

        fun holdNextDispatch() = arm(dispatchGate)

        fun parserClasspath() = requests.single().classpath

        fun afterWorkspaceScan(): Gate {
            val gate = Gate()
            allGates.add(gate)
            afterScan.set {
                if (service.renameWalks.walks > 0) {
                    afterScan.set(null)
                    gate.hold()
                }
            }
            return gate
        }

        private fun arm(slot: AtomicReference<Gate?>): Gate {
            val gate = Gate()
            allGates.add(gate)
            check(slot.compareAndSet(null, gate))
            return gate
        }

        fun drainRequestEntries() {
            executor.submit {}.get(10, TimeUnit.SECONDS)
        }

        fun assertSingleParse() {
            val request = requests.single()
            assertEquals(source, request.content)
            assertEquals(uri, request.uri)
            assertTrue(request.compilePhase >= Phases.SEMANTIC_ANALYSIS)
            val parsed = parses.single()
            assertNotNull(parsed.ast)
            assertTrue(parsed.outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
        }

        fun assertEdits(edit: WorkspaceEdit) {
            val change = edit.documentChanges.single()
            assertTrue(change.isLeft, "local rename must produce only a versioned text edit")
            assertEquals(uri.toString(), change.left.textDocument.uri)
            assertEquals(41, change.left.textDocument.version)
            assertEquals(
                listOf(
                    TextEdit(Range(Position(0, 4), Position(0, 9)), "sum"),
                    TextEdit(Range(Position(1, 8), Position(1, 13)), "sum"),
                ),
                change.left.edits.sortedBy { it.range.start.line },
            )
            assertOriginal()
        }

        fun assertOriginal() {
            assertEquals(source, documents.get(uri))
            assertEquals(DocumentVersion.of(41), documents.getSnapshot(uri)?.version)
        }

        override fun close() {
            allGates.forEach(Gate::open)
            scope.cancel()
            compilation.shutdown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun replacement() = WorkerDescriptor(
            id = "rename-replacement",
            supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("5.0.0")),
            capabilities = WorkerCapabilities(),
            connector = WorkerConnector.InProcess,
        )

        fun <T> CompletableFuture<T>.result(): T = get(10, TimeUnit.SECONDS)

        fun errorFrom(answer: CompletableFuture<*>): ResponseErrorException {
            val failure = assertThrows<ExecutionException> { answer.result() }
            return assertIs<ResponseErrorException>(failure.cause)
        }

        fun assertModified(answer: CompletableFuture<*>) =
            assertEquals(ResponseErrorCode.ContentModified.value, errorFrom(answer).responseError.code)
    }
}
