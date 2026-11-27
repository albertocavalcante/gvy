// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.test.TEST_GROOVY_VERSION
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.gvy.source.DocumentVersion
import io.mockk.coEvery
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
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also {
                    fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(42))
                }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover recompiles its open buffer if initialization invalidates the awaited cache`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation()
                fixture.compilation.clearCaches()
                null
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertContains(hover.contents.right.value, "String value()")
        }
    }

    @Test
    fun `hover after startup's second invalidation answers the captured text or ContentModified`(): Unit = runBlocking {
        fixture().use { fixture ->
            // The CI interleaving of #2457: worker selection clears the cache after the awaited compile, then
            // the workspace model changes (and clears again) while hover compiles the buffer itself.
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also { fixture.compilation.clearCaches() }
            }
            coEvery { fixture.compilation.compile(fixture.uri, any(), any()) } coAnswers {
                fixture.compile(secondArg<String>()).also { fixture.addSourceRoot() }
            }
            coEvery { fixture.compilation.compileTransient(fixture.uri, any(), any()) } coAnswers {
                fixture.compileTransient(secondArg<String>()).also { fixture.addSourceRoot() }
            }

            val outcome = runCatching { fixture.endpoint.hover(fixture.params()).await() }

            val hover = outcome.getOrNull()
            if (hover != null) {
                assertContains(hover.contents.right.value, "String value()")
            } else {
                val error = assertIs<ResponseErrorException>(outcome.exceptionOrNull())
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `hover rejects its own compile when the workspace classpath changes during it`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also { fixture.compilation.clearCaches() }
            }
            coEvery { fixture.compilation.compileTransient(fixture.uri, any(), any()) } coAnswers {
                // Parsed against the old classpath; a new one is published before hover can answer from it.
                fixture.compileTransient(secondArg<String>()).also { fixture.addDependency() }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects its own compile when the worker selection changes during it`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also { fixture.compilation.clearCaches() }
            }
            coEvery { fixture.compilation.compileTransient(fixture.uri, any(), any()) } coAnswers {
                fixture.compileTransient(secondArg<String>()).also {
                    val worker = defaultWorkerDescriptors().first()
                    assertTrue(fixture.compilation.updateGroovySelection(TEST_GROOVY_VERSION, worker))
                }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover reports a failed fallback compile as an internal error without a stack trace`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also { fixture.compilation.clearCaches() }
            }
            coEvery { fixture.compilation.compileTransient(fixture.uri, any(), any()) } throws
                IOException("classpath entry vanished")

            val error =
                assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

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
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } returns null
            var captures = 0
            every { fixture.compilation.getCompiledSnapshot(fixture.uri) } answers {
                captures++
                if (captures == 1) null else callOriginal()
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertEquals("", hover.contents.right.value)
            assertEquals(ORIGINAL_SOURCE, fixture.compilation.getCompiledSnapshot(fixture.uri)?.sourceText)
        }
    }

    @Test
    fun `hover rejects another edit after a completed incomplete parse`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                val result = assertNotNull(fixture.acquireCompilation())
                assertTrue(result.parseCompleted)
                assertNull(result.ast)
                fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(43))
                result
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            verify(exactly = 0) { fixture.compilation.getCompiledSnapshot(fixture.uri) }
        }
    }

    @Test
    fun `hover cannot treat an incomplete parse of different text as current`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            val result = assertNotNull(fixture.acquireCompilation())
            fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(43))
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } returns result

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            // The buffer is unchanged since the capture, so only the new source check can reject this result.
            // The previous handler fell through to the stale cache and rejected it there.
            verify(exactly = 0) { fixture.compilation.getCompiledSnapshot(fixture.uri) }
            verify(exactly = 0) { fixture.compilation.getHoverSession(fixture.uri, any()) }
        }
    }

    @Test
    fun `hover does not classify an unfinished compilation as unavailable syntax`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(fixture.uri, INCOMPLETE_SOURCE, DocumentVersion.of(42))
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } returns
                CompilationResult.failure(emptyList(), INCOMPLETE_SOURCE)

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

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
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                val result = assertNotNull(fixture.acquireCompilation())
                assertTrue(result.parseCompleted)
                assertNull(result.ast)
                fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(42))
                result
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            verify(exactly = 0) { fixture.compilation.getCompiledSnapshot(fixture.uri) }
        }
    }

    @Test
    fun `closed file hover ignores a valid stale cache when the disk text is incomplete`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.remove(fixture.uri)
            Path.of(fixture.uri).writeText(INCOMPLETE_SOURCE)
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.compile(INCOMPLETE_SOURCE)
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            // A valid cache for ORIGINAL_SOURCE exists, and a closed file has no captured text to compare against.
            // The previous handler served that cache; its method signature must not appear.
            assertEquals("", hover.contents.right.value)
            verify(exactly = 0) { fixture.compilation.getHoverSession(fixture.uri, any()) }
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
            every { fixture.compilation.getCompiledSnapshot(fixture.uri) } answers {
                val captured = assertNotNull(callOriginal())
                assertEquals(CURRENT_SOURCE, captured.sourceText)
                assertNotSame(original, captured.parseResult)
                captures++
                published = cache.publish(cache.ownership.begin(fixture.uri), ORIGINAL_SOURCE, original, fingerprint)
                captured
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
            val original = assertNotNull(fixture.compilation.getParseResult(fixture.uri))
            val newer = fixture.compilation.compileTransient(fixture.uri, CURRENT_SOURCE)
            assertNotNull(newer.ast)
            val cache = fixture.compilation.compilationCacheService
            val fingerprint = fixture.compilation.workspaceManager.getConfigurationFingerprint()
            var captures = 0
            var published = false
            every { fixture.compilation.getCompiledSnapshot(fixture.uri) } answers {
                val captured = assertNotNull(callOriginal())
                assertEquals(ORIGINAL_SOURCE, captured.sourceText)
                assertSame(original, captured.parseResult)
                captures++
                published = cache.publish(cache.ownership.begin(fixture.uri), CURRENT_SOURCE, newer, fingerprint)
                captured
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
            every { fixture.compilation.getCompiledSnapshot(fixture.uri) } answers {
                val captured = assertNotNull(callOriginal())
                captures++
                updated = fixture.documents.update(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(42)) != null
                captured
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
            every { fixture.compilation.getCompiledSnapshot(fixture.uri) } answers {
                val captured = assertNotNull(callOriginal())
                captures++
                fixture.documents.remove(fixture.uri)
                removed = fixture.documents.getSnapshot(fixture.uri) == null
                fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(41))
                reopened = fixture.documents.getIncarnation(fixture.uri) !== incarnation
                captured
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
            every { fixture.compilation.getCompiledSnapshot(fixture.uri) } answers {
                assertNotNull(callOriginal())
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
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also {
                    fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(42))
                }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects closing the buffer during acquisition`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                fixture.acquireCompilation().also { fixture.documents.remove(fixture.uri) }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a version change while creating the session`(): Unit = runBlocking {
        fixture().use { fixture ->
            every { fixture.compilation.getHoverSession(fixture.uri, any()) } answers {
                callOriginal().also { fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, DocumentVersion.of(42)) }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a cache that still contains a different source`(): Unit = runBlocking {
        fixture().use { fixture ->
            val stale = assertNotNull(fixture.compilation.ensureCompiled(fixture.uri))
            fixture.documents.put(fixture.uri, CURRENT_SOURCE, DocumentVersion.of(42))
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } returns stale

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover propagates compilation cancellation`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> { fixture.endpoint.hover(fixture.params()).await() }
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
        val compilation =
            GroovyCompilationService(documentProvider = documents, engineConfig = EngineConfiguration(engine))
        compilation.workspaceManager.initializeWorkspace(workspace)
        assertTrue(compilation.compile(uri, ORIGINAL_SOURCE).isSuccess)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return Fixture(uri, documents, compilation, scope)
    }

    private data class Fixture(
        val uri: URI,
        val documents: DocumentProvider,
        private val actual: GroovyCompilationService,
        val scope: CoroutineScope,
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
