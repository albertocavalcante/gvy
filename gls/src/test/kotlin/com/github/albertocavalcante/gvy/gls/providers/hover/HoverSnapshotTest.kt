package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
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
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HoverSnapshotTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `core hover reads the accepted buffer before debounced compilation`(): Unit = runBlocking {
        fixture(EngineType.Core).use { fixture ->
            fixture.change(CURRENT_SOURCE, 42)

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertContains(hover.contents.right.value, "Integer value()")
            assertEquals(CURRENT_SOURCE, fixture.documents.get(fixture.uri))
        }
    }

    @Test
    fun `hover recompiles its open buffer if initialization invalidates the awaited cache`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                callOriginal()
                fixture.compilation.clearCaches()
                null
            }

            val hover = fixture.endpoint.hover(fixture.params()).await()

            assertContains(hover.contents.right.value, "String value()")
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
    fun `native hover retains captured facts when an older parse replaces the cache`(): Unit = runBlocking {
        fixture(EngineType.Native).use { fixture ->
            val original = assertNotNull(fixture.compilation.getParseResult(fixture.uri))
            fixture.change(CURRENT_SOURCE, 42)
            val cache = fixture.compilation.compilationCacheService
            mockkObject(cache)
            try {
                every { cache.getCachedWithContent(fixture.uri) } answers {
                    callOriginal().also { cache.putCached(fixture.uri, ORIGINAL_SOURCE, original) }
                }

                val hover = fixture.endpoint.hover(fixture.params()).await()

                assertContains(hover.contents.right.value, "Integer")
                assertEquals(CURRENT_SOURCE, fixture.documents.get(fixture.uri))
                assertEquals(ORIGINAL_SOURCE, cache.getCachedWithContent(fixture.uri)?.first)
            } finally {
                unmockkObject(cache)
            }
        }
    }

    @Test
    fun `hover rejects a version change during compilation acquisition`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                callOriginal().also { fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, 42) }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects closing the buffer during acquisition`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                callOriginal().also { fixture.documents.remove(fixture.uri) }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a version change while creating the session`(): Unit = runBlocking {
        fixture().use { fixture ->
            every { fixture.compilation.getHoverSession(fixture.uri, any()) } answers {
                callOriginal().also { fixture.documents.put(fixture.uri, ORIGINAL_SOURCE, 42) }
            }

            val error = assertFailsWith<ResponseErrorException> { fixture.endpoint.hover(fixture.params()).await() }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `hover rejects a cache that still contains a different source`(): Unit = runBlocking {
        fixture().use { fixture ->
            val stale = assertNotNull(fixture.compilation.ensureCompiled(fixture.uri))
            fixture.documents.put(fixture.uri, CURRENT_SOURCE, 42)
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

    private suspend fun fixture(engine: EngineType = EngineType.Core): Fixture {
        val uri = workspace.resolve("Sample.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, ORIGINAL_SOURCE, 41) }
        val compilation = spyk(
            GroovyCompilationService(documentProvider = documents, engineConfig = EngineConfiguration(engine)),
        )
        compilation.workspaceManager.initializeWorkspace(workspace)
        assertTrue(compilation.compile(uri, ORIGINAL_SOURCE).isSuccess)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val endpoint = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        return Fixture(uri, documents, compilation, scope, endpoint)
    }

    private data class Fixture(
        val uri: URI,
        val documents: DocumentProvider,
        val compilation: GroovyCompilationService,
        val scope: CoroutineScope,
        val endpoint: GroovyTextDocumentService,
    ) : AutoCloseable {
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
    }
}
