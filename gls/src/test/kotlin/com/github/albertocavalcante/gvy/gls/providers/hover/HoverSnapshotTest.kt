// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition

@OptIn(ExperimentalCoroutinesApi::class)
class HoverSnapshotTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `legacy core alias reads the accepted Native buffer before debounced compilation`(): Unit = runBlocking {
        fixture(EngineType.fromString("core")).use { fixture ->
            fixture.change(CURRENT_SOURCE, 42)

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertContains(hover.contents.right.value, "Integer value()")
            assertEquals(CURRENT_SOURCE, fixture.documents.get(fixture.uri))
        }
    }

    @Test
    fun `legacy core alias rejects a changed buffer instead of returning an earlier source`(): Unit = runBlocking {
        fixture(EngineType.fromString("core")).use { fixture ->
            fixture.onParsed.set {
                fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(42))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover recompiles its open buffer if initialization invalidates the awaited cache`(): Unit = runBlocking {
        val expected = fixture().use { control -> control.endpoint.hover(control.params()).await() }
        assertContains(expected.contents.right.value, "String value()")
        fixture().use { fixture ->
            val requested = AtomicInteger()
            fixture.transformRequest.set { request ->
                if (requested.incrementAndGet() == 1) {
                    request.copy(content = ORIGINAL_SOURCE.replace("String", "Object"))
                } else {
                    request
                }
            }
            fixture.onParsed.set { parsed ->
                val returnType = parsed.ast?.classes?.flatMap { it.methods }
                    ?.first { it.name == "value" }?.returnType?.nameWithoutPackage
                if (fixture.parseCount.get() == 1) {
                    assertEquals("Object", returnType)
                    fixture.compilation.clearCaches()
                } else {
                    assertEquals("String", returnType)
                }
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertEquals(2, fixture.parseCount.get())
            assertEquals(2, requested.get())
            assertEquals(expected, hover)
        }
    }

    @Test
    fun `hover rejects model change before startup cache retirement can retry`(): Unit = runBlocking {
        fixture().use { fixture ->
            val firstParsed = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val traversals = AtomicInteger()
            fixture.onTraversal.set { _, _ -> traversals.incrementAndGet() }
            fixture.onParsed.set {
                firstParsed.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            try {
                val answer = fixture.endpoint.hover(fixture.params())
                assertTrue(firstParsed.await(5, TimeUnit.SECONDS), "First real worker must finish parsing")
                fixture.addSourceRoot()
                fixture.compilation.clearCaches()
                releaseFirst.countDown()

                val error = assertFailsWith<ResponseErrorException> { answer.await() }
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
                assertEquals(1, fixture.parseCount.get())
                assertEquals(0, traversals.get())
                verify(exactly = 0) { fixture.compilation.getHoverSession(fixture.uri, any(), any(), any()) }
            } finally {
                releaseFirst.countDown()
            }
        }
    }

    @Test
    fun `hover rejects model change during second startup producer`(): Unit = runBlocking {
        fixture().use { fixture ->
            val firstParsed = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondParsed = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            val traversals = AtomicInteger()
            fixture.onTraversal.set { _, _ -> traversals.incrementAndGet() }
            fixture.onParsed.set {
                when (fixture.parseCount.get()) {
                    1 -> {
                        firstParsed.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS))
                    }
                    2 -> {
                        secondParsed.countDown()
                        check(releaseSecond.await(10, TimeUnit.SECONDS))
                    }
                    else -> error("Unexpected additional hover producer")
                }
            }
            try {
                val answer = fixture.endpoint.hover(fixture.params())
                assertTrue(firstParsed.await(5, TimeUnit.SECONDS), "First real worker must finish parsing")
                fixture.compilation.clearCaches()
                releaseFirst.countDown()
                assertTrue(secondParsed.await(5, TimeUnit.SECONDS), "Fresh second worker must finish parsing")
                fixture.addSourceRoot()
                releaseSecond.countDown()

                val error = assertFailsWith<ResponseErrorException> { answer.await() }
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
                assertEquals(2, fixture.parseCount.get())
                assertEquals(0, traversals.get())
                verify(exactly = 0) { fixture.compilation.getHoverSession(fixture.uri, any(), any(), any()) }
            } finally {
                releaseFirst.countDown()
                releaseSecond.countDown()
            }
        }
    }

    @Test
    fun `hover rejects its own compile when the workspace classpath changes during it`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.onParsed.set { fixture.addDependency() }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects its own compile when the worker selection changes during it`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.onParsed.set {
                assertTrue(fixture.compilation.updateSelectedWorker(defaultWorkerDescriptors().first()))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover reports a failed fallback compile as an internal error without a stack trace`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.onParsed.set { throw IOException("classpath entry vanished") }

            val error =
                assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
            assertContains(error.responseError.message, "classpath entry vanished")
            assertNull(error.responseError.data)
        }
    }

    @Test
    fun `native hover reads the accepted buffer before debounced compilation`(): Unit = runBlocking {
        fixture(EngineType.Native).use { fixture ->
            fixture.change(CURRENT_SOURCE, 42)

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertContains(hover.contents.right.value, "Integer")
        }
    }

    @Test
    fun `hover does not mistake an incomplete current parse for a changed document`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            val input = assertNotNull(fixture.documents.getSnapshot(fixture.uri))
            val result = assertNotNull(fixture.acquireCompilation())
            assertTrue(result.parseCompleted)
            assertEquals(INCOMPLETE_SOURCE, result.sourceText)
            assertNull(result.ast, "The real parser must reproduce the missing current AST")
            assertEquals(ORIGINAL_SOURCE, fixture.compilation.getCompiledSnapshot(fixture.uri)?.sourceText)

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertEquals("", hover.contents.right.value, "An older method signature must not escape")
            assertSame(input, fixture.documents.getSnapshot(fixture.uri))
        }
    }

    @Test
    fun `hover does not use a stale cache after its fallback parses incomplete source`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            fixture.onParsed.set { parsed ->
                assertNull(parsed.ast)
                assertEquals(ORIGINAL_SOURCE, fixture.compilation.getCompiledSnapshot(fixture.uri)?.sourceText)
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertEquals(1, fixture.parseCount.get())
            assertEquals("", hover.contents.right.value)
            assertEquals(ORIGINAL_SOURCE, fixture.compilation.getCompiledSnapshot(fixture.uri)?.sourceText)
        }
    }

    @Test
    fun `hover rejects another edit after a completed incomplete parse`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            fixture.onParsed.set { parsed ->
                assertNull(parsed.ast)
                fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(43))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            assertEquals(1, fixture.parseCount.get())
        }
    }

    @Test
    fun `hover cannot treat an incomplete parse of different text as current`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            fixture.onParsed.set { parsed ->
                assertNull(parsed.ast)
                fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(43))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover does not classify an unfinished compilation as unavailable syntax`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            val traversals = AtomicInteger()
            fixture.onTraversal.set { _, _ -> traversals.incrementAndGet() }
            fixture.onParsed.set { parsed ->
                assertNull(parsed.ast)
                fixture.compilation.clearCaches()
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(2, fixture.parseCount.get())
            assertEquals(0, traversals.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `closed file hover remains useful and reports an incomplete parse without stale facts`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.remove(fixture.uri)
            fixture.compilation.invalidateCache(fixture.uri)
            Path.of(fixture.uri).writeText(CURRENT_SOURCE)

            val valid = fixture.endpoint.hover(fixture.params()).await()
            assertContains(valid.contents.right.value, "Integer")

            Path.of(fixture.uri).writeText(INCOMPLETE_SOURCE)
            fixture.compilation.invalidateCache(fixture.uri)
            val incomplete = fixture.endpoint.hover(fixture.params()).await()

            assertEquals("", incomplete.contents.right.value)
            assertNull(fixture.documents.getSnapshot(fixture.uri))
        }
    }

    @Test
    fun `hover rejects an open during an incomplete disk compilation`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.remove(fixture.uri)
            fixture.compilation.invalidateCache(fixture.uri)
            Path.of(fixture.uri).writeText(INCOMPLETE_SOURCE)
            fixture.onParsed.set { parsed ->
                assertNull(parsed.ast)
                fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(42))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `closed file hover ignores a valid stale cache when the disk text is incomplete`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.remove(fixture.uri)
            Path.of(fixture.uri).writeText(INCOMPLETE_SOURCE)
            assertEquals(ORIGINAL_SOURCE, fixture.compilation.getCompiledSnapshot(fixture.uri)?.sourceText)
            fixture.onParsed.set { parsed -> assertNull(parsed.ast) }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            // The old valid cache cannot replace the actual incomplete disk parse.
            assertEquals(1, fixture.parseCount.get())
            assertEquals("", hover.contents.right.value)
            assertNull(fixture.documents.getSnapshot(fixture.uri))
        }
    }

    @Test
    fun `native hover retains captured facts when an older parse replaces the cache`(): Unit = runBlocking {
        fixture(EngineType.Native).use { fixture ->
            val original = assertNotNull(fixture.compilation.getParseResult(fixture.uri))
            fixture.change(CURRENT_SOURCE, 42)
            val cache = fixture.compilation.compilationCacheService
            val fingerprint = fixture.compilation.workspaceManager.getConfigurationFingerprint()
            var captures = 0
            var published = false
            fixture.onTraversal.set { request, parsed ->
                assertEquals(CURRENT_SOURCE, request.content)
                assertNotSame(original, parsed)
                captures++
                published = cache.publish(cache.ownership.begin(fixture.uri), ORIGINAL_SOURCE, original, fingerprint)
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertEquals(1, captures, "The replacement must occur after exactly one snapshot capture")
            assertTrue(published, "The older parse must actually replace the cache")
            assertEquals(ORIGINAL_SOURCE, cache.getCachedWithContent(fixture.uri, fingerprint)?.first)
            assertContains(hover.contents.right.value, "Integer")
            assertEquals(CURRENT_SOURCE, fixture.documents.get(fixture.uri))
        }
    }

    @Test
    fun `native hover retains an older capture when a newer parse replaces the cache`(): Unit = runBlocking {
        fixture().use { fixture ->
            assertNotNull(fixture.compilation.getParseResult(fixture.uri))
            val newer = fixture.compilation.compileTransient(fixture.uri, CURRENT_SOURCE)
            assertNotNull(newer.ast)
            val cache = fixture.compilation.compilationCacheService
            val fingerprint = fixture.compilation.workspaceManager.getConfigurationFingerprint()
            var captures = 0
            var published = false
            fixture.onTraversal.set { request, parsed ->
                assertEquals(ORIGINAL_SOURCE, request.content)
                assertNotSame(newer, parsed)
                captures++
                published = cache.publish(cache.ownership.begin(fixture.uri), CURRENT_SOURCE, newer, fingerprint)
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertEquals(1, captures)
            assertTrue(published, "The newer parse must actually replace the cache")
            assertEquals(CURRENT_SOURCE, cache.getCachedWithContent(fixture.uri, fingerprint)?.first)
            assertContains(hover.contents.right.value, "String value()")
            assertEquals(ORIGINAL_SOURCE, fixture.documents.get(fixture.uri))
        }
    }

    @Test
    fun `hover rejects a version change during snapshot capture`(): Unit = runBlocking {
        fixture().use { fixture ->
            val incarnation = assertNotNull(fixture.documents.getIncarnation(fixture.uri))
            var captures = 0
            var updated = false
            fixture.onParsed.set {
                captures++
                updated = fixture.documents.update(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(42)) != null
            }

            val result = runCatching { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, captures)
            assertTrue(updated, "The version update must be accepted during capture")
            assertSame(incarnation, fixture.documents.getIncarnation(fixture.uri))
            assertEquals(ORIGINAL_SOURCE, fixture.documents.getSnapshot(fixture.uri)?.content)
            assertEquals(DocumentVersion.of(42), fixture.documents.getSnapshot(fixture.uri)?.version)
            val error = assertIs<ResponseErrorException>(result.exceptionOrNull())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a closed and reopened identical buffer during snapshot capture`(): Unit = runBlocking {
        fixture().use { fixture ->
            val incarnation = assertNotNull(fixture.documents.getIncarnation(fixture.uri))
            var captures = 0
            var removed = false
            var reopened = false
            fixture.onParsed.set {
                captures++
                fixture.documents.remove(fixture.uri)
                removed = fixture.documents.getSnapshot(fixture.uri) == null
                fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(41))
                reopened = fixture.documents.getIncarnation(fixture.uri) !== incarnation
            }

            val result = runCatching { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, captures)
            assertTrue(removed, "The captured buffer must be closed during capture")
            assertTrue(reopened, "A new buffer incarnation must be opened during capture")
            assertNotSame(incarnation, fixture.documents.getIncarnation(fixture.uri))
            assertEquals(ORIGINAL_SOURCE, fixture.documents.getSnapshot(fixture.uri)?.content)
            assertEquals(DocumentVersion.of(41), fixture.documents.getSnapshot(fixture.uri)?.version)
            val error = assertIs<ResponseErrorException>(result.exceptionOrNull())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover propagates cancellation during snapshot capture`(): Unit = runBlocking {
        fixture().use { fixture ->
            var captures = 0
            fixture.onParsed.set {
                captures++
                throw CancellationException("cancelled during snapshot capture")
            }

            val result = runCatching { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, captures)
            assertIs<CancellationException>(result.exceptionOrNull())
        }
    }

    @Test
    fun `hover rejects a version change during compilation acquisition`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.onParsed.set {
                fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(42))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects closing the buffer during acquisition`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.onParsed.set { fixture.documents.remove(fixture.uri) }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a version change while creating the session`(): Unit = runBlocking {
        fixture().use { fixture ->
            every { fixture.compilation.getHoverSession(fixture.uri, any(), any(), any()) } answers {
                callOriginal().also { fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(42)) }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            verify(exactly = 1) { fixture.compilation.getHoverSession(fixture.uri, any(), any(), any()) }
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a cache that still contains a different source`(): Unit = runBlocking {
        fixture().use { fixture ->
            assertNotNull(fixture.compilation.ensureCompiled(fixture.uri))
            fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(42))
            fixture.onParsed.set {
                assertEquals(ORIGINAL_SOURCE, fixture.compilation.getCompiledSnapshot(fixture.uri)?.sourceText)
                fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(43))
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(1, fixture.parseCount.get())
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover propagates compilation cancellation`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.onParsed.set { throw CancellationException("cancelled") }

            assertFailsWith<CancellationException> { fixture.endpoint.hover(fixture.params()).await() }
            assertEquals(1, fixture.parseCount.get())
        }
    }

    @Test
    fun `unchanged hover retains its method signature`(): Unit = runBlocking {
        fixture().use { fixture ->
            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertContains(hover.contents.right.value, "String value()")
        }
    }

    private suspend fun fixture(engine: EngineType = EngineType.Native): Fixture {
        val uri = workspace.resolve("Sample.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, ORIGINAL_SOURCE, DocumentVersion.of(41)) }
        val transformRequest = AtomicReference<(ParseRequest) -> ParseRequest>({ it })
        val onParsed = AtomicReference<(ParseResult) -> Unit>({})
        val onTraversal = AtomicReference<(ParseRequest, ParseResult) -> Unit>({ _, _ -> })
        val parseCount = AtomicInteger()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            engineConfig = EngineConfiguration(engine),
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(transformRequest.get().invoke(request))
                        parseCount.incrementAndGet()
                        onParsed.get().invoke(parsed)
                        val model = object : GroovyAstModel by parsed.astModel {
                            override fun getNodeAt(uri: URI, position: GroovyPosition): ASTNode? =
                                parsed.astModel.getNodeAt(uri, position).also {
                                    onTraversal.get().invoke(request, parsed)
                                }
                        }
                        return parsed.copy(astModel = model)
                    }
                }
            },
        )
        compilation.workspaceManager.initializeWorkspace(workspace)
        assertTrue(compilation.compile(uri, ORIGINAL_SOURCE).isSuccess)
        parseCount.set(0)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return Fixture(uri, documents, compilation, scope, transformRequest, onParsed, onTraversal, parseCount)
    }

    private data class Fixture(
        val uri: URI,
        val documents: DocumentProvider,
        private val actual: GroovyCompilationService,
        val scope: CoroutineScope,
        val transformRequest: AtomicReference<(ParseRequest) -> ParseRequest>,
        val onParsed: AtomicReference<(ParseResult) -> Unit>,
        val onTraversal: AtomicReference<(ParseRequest, ParseResult) -> Unit>,
        val parseCount: AtomicInteger,
    ) : AutoCloseable {
        val compilation = spyk(actual)
        val endpoint = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        init {
            assertSame(actual.compilationCacheService, compilation.compilationCacheService)
            assertSame(actual.symbolIndexingService, compilation.symbolIndexingService)
        }

        // Delegate normally: a suspend spy's callOriginal cannot await COROUTINE_SUSPENDED.
        suspend fun acquireCompilation() = actual.ensureCompiled(uri)

        suspend fun compile(source: String) = actual.compile(uri, source)

        suspend fun compileTransient(source: String) = actual.compileTransient(uri, source)

        /** Publishes a workspace model with another source root, as startup does once the build model loads. */
        fun addSourceRoot() {
            val root = Path.of(uri).parent
            actual.updateWorkspaceModel(root, emptyList(), listOf(root.resolve("src").createDirectories()))
        }

        /** Publishes a workspace model with one more classpath entry. */
        fun addDependency() {
            val root = Path.of(uri).parent
            actual.updateWorkspaceModel(root, listOf(root.resolve("classes").createDirectories()), emptyList())
        }

        fun params() = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, 23))

        fun change(source: String, version: Int) = endpoint.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri.toString(), version),
                listOf(TextDocumentContentChangeEvent(source)),
            ),
        )

        override fun close() {
            scope.cancel()
            compilation.clearCaches()
        }
    }

    private companion object {
        const val ORIGINAL_SOURCE = "class Sample { String value() { 'before' } }\n"
        const val CURRENT_SOURCE = "class Sample { Integer value() { 42 } }\n"
        const val INCOMPLETE_SOURCE = "class Sample { Integer value() { OrderStatus. } }\n"
    }
}
