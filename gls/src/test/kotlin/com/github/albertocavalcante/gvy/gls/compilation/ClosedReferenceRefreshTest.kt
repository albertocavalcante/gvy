// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClosedReferenceRefreshTest {
    @Test
    fun `a confirmed path replaced by a directory is refused before any body read`(@TempDir root: Path) {
        val parses = AtomicInteger()
        Fixture(root) { parses.incrementAndGet() }.use { fixture ->
            Files.delete(fixture.closed)
            Files.createDirectory(fixture.closed)
            val directory = ObservedDirectory(fixture.closed)
            assertNull(readRegularSnapshotSource(directory.path))
            fixture.refresh(directory.path)
            val failed = assertFailsWith<ExecutionException> { fixture.incoming().get(10, TimeUnit.SECONDS) }
            val response = assertIs<ResponseErrorException>(failed.cause).responseError
            assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
            assertEquals("Reference index unavailable for ${fixture.closed.toUri()}", response.message)
            assertEquals(0, directory.bodyReads.get(), "A directory must be rejected before opening a channel")
            assertEquals(0, parses.get())
        }
    }

    @Test
    fun `closed reference acquisition preserves a worker IO failure`(@TempDir root: Path) {
        // A filesystem exception carries the failing path through coroutine stacktrace recovery.
        val unavailable = FileSystemException("closed-compiler.jar", null, "Closed compiler unavailable")
        Fixture(root) { throw unavailable }.use { fixture ->
            val failed = assertFailsWith<ExecutionException> { fixture.incoming().get(10, TimeUnit.SECONDS) }
            assertSame(unavailable, failed.cause)
        }
    }

    @Test
    fun `a child compiler failure after an origin edit reports content modified`(@TempDir root: Path) {
        val documents = spyk(DocumentProvider())
        val replacement = "class Origin { def changed() {} }"
        lateinit var child: ParseRequest
        Fixture(root, documents) { request ->
            child = request
            throw FileSystemException("closed-compiler.jar", null, "Closed compiler unavailable")
        }.use { fixture ->
            val origin = fixture.origin.toUri()
            val original = fixture.compilation.captureSnapshotSource(origin)
            val peer = fixture.closed.toUri()
            val publication = assertNotNull(original.dependencies.semanticIndexer?.currentReferencePublication(peer))
            assertTrue(publication.diskConfirmed)
            val producer = assertNotNull(publication.producer)
            assertFalse(producer.ownsWindow(original.workspace, original.ticket, original.inventory.identity))
            // The real closed capture reads its buffer and the companion inventory under the same lifecycle lock.
            every { documents.getBuffer(peer) } answers {
                if (original.isCurrent()) assertNotNull(documents.update(origin, replacement, DocumentVersion.of(1)))
                callOriginal()
            }
            val failed = assertFailsWith<ExecutionException> { fixture.incoming().get(10, TimeUnit.SECONDS) }
            assertFalse(original.isCurrent())
            assertEquals(peer, child.uri)
            assertEquals(replacement, child.openSources[origin])
            val response = assertIs<ResponseErrorException>(failed.cause).responseError
            assertEquals(ResponseErrorCode.ContentModified.value, response.code)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compatible incoming requests share one closed compiler invocation`(
        cancelFirst: Boolean,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parses = AtomicInteger()
        Fixture(root) {
            parses.incrementAndGet()
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
        }.use { fixture ->
            try {
                val first = fixture.incoming()
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val second = fixture.incoming()
                withTimeout(10_000) {
                    while (parses.get() == 1 && fixture.reservations() < 2) yield()
                }
                if (cancelFirst) {
                    assertTrue(first.cancel(true))
                    assertFailsWith<CancellationException> { first.join() }
                }
                release.countDown()
                val answers = if (cancelFirst) listOf(second) else listOf(first, second)
                for (answer in answers) {
                    val caller = answer.get(10, TimeUnit.SECONDS).single()
                    assertEquals("caller", caller.from.name)
                    assertEquals(fixture.origin.toUri().toString(), caller.from.uri)
                    assertEquals(fixture.callColumn, caller.fromRanges.single().start.character)
                }
                assertEquals(1, parses.get(), "Both requests reached the same physical closed compiler invocation")
            } finally {
                release.countDown()
            }
        }
    }

    /** Keep the physical URI and attributes. Observe channel opens on this path alone. */
    private class ObservedDirectory(directory: Path) {
        val bodyReads = AtomicInteger()
        private val observer = mockk<FileSystemProvider>()
        private val fs = mockk<FileSystem> { every { provider() } returns observer }
        val path = object : Path by directory {
            override fun getFileSystem(): FileSystem = fs
        }

        init {
            val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java)
            assertTrue(attributes.isDirectory)
            every { observer.readAttributes(path, BasicFileAttributes::class.java, *anyVararg()) } returns attributes
            every { observer.newByteChannel(path, any(), *anyVararg()) } answers {
                bodyReads.incrementAndGet()
                throw IOException("Directory body read")
            }
        }
    }

    private class Fixture(
        root: Path,
        documents: DocumentProvider = DocumentProvider(),
        beforeClosedParse: (ParseRequest) -> Unit,
    ) : AutoCloseable {
        val origin = root.resolve("Origin.groovy")
        val closed = root.resolve("Peer.groovy")
        private val source = "class Origin { def target() {}; def caller() { target() } }"
        val callColumn = source.lastIndexOf("target")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile
        private var armed = false
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        if (armed && request.uri == closed.toUri()) beforeClosedParse(request)
                        return delegate.parse(request)
                    }
                }
            },
        )
        private val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        private val item: CallHierarchyItem

        init {
            Files.writeString(origin, source)
            Files.writeString(closed, "class Peer {}")
            runBlocking {
                compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
                compilation.getWorkspaceCompiler().compileWorkspace()
                documents.put(origin.toUri(), source)
                compilation.onDocumentOpened(origin.toUri())
                compilation.compile(origin.toUri(), source)
            }
            item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(
                    TextDocumentIdentifier(origin.toUri().toString()),
                    Position(0, source.indexOf("target")),
                ),
            ).get(10, TimeUnit.SECONDS).single()
            armed = true
        }

        fun refresh(candidate: Path) = runBlocking {
            compilation.refreshClosedReferences(candidate, ParseMode.WORKSPACE)
        }

        fun incoming() = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))

        fun reservations(): Int =
            compilation.compilationCacheService.snapshotJobs[closed.toUri()]?.identity?.delivery?.enrolledWaiters ?: 0

        override fun close() {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
