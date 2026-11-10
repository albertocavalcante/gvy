package com.github.albertocavalcante.gvy.gls.providers.implementation

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.crossfile.CrossFileOperationsHandler
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Execution(ExecutionMode.SAME_THREAD) // The controlled parse interleaving scopes a constructor mock.
class ImplementationAcquisitionTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `class navigation survives invalidation after compilation and before snapshot capture`(): Unit = runBlocking {
        assertSurvivesInvalidation(Position(0, 11), 0)
    }

    @Test
    fun `method navigation survives invalidation after compilation and before snapshot capture`(): Unit = runBlocking {
        assertSurvivesInvalidation(Position(1, 12), 1)
    }

    @Test
    fun `origin invalidation reestablishes the query document before capture`(): Unit = runBlocking {
        assertSurvivesInvalidation(Position(0, 11), 0) { it.contract }
    }

    @Test
    fun `implementation invalidation reestablishes the target before capture`(): Unit = runBlocking {
        assertSurvivesInvalidation(Position(1, 12), 1) { it.implementation }
    }

    @Test
    fun `a late invalidated compile cannot leave a cached implementation without semantic facts`(): Unit = runBlocking {
        assertLatePublication(closedOrigin = false)
    }

    @Test
    fun `a closed origin compile retired by invalidation still yields current implementations`(): Unit = runBlocking {
        assertLatePublication(closedOrigin = true)
    }

    private suspend fun CoroutineScope.assertLatePublication(closedOrigin: Boolean) {
        val parsed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquiring = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val armed = AtomicBoolean()
        val target = AtomicReference<URI?>()
        mockkConstructor(InProcessWorkerSession::class)
        pauseParsedDocument(armed, target, parsed, release)
        try {
            fixture(closedOrigin = closedOrigin).use { fixture ->
                val document = if (closedOrigin) fixture.contract else fixture.implementation
                val text = checkNotNull(fixture.documents.get(document))
                if (closedOrigin) fixture.documents.remove(document)
                target.set(document)
                fixture.compilation.invalidateCache(document)
                armed.set(true)
                val background = fixture.compilation.compileAsync(
                    this,
                    document,
                    text,
                )
                assertTrue(parsed.await(10, TimeUnit.SECONDS), "The real target parse must finish before invalidation")
                // Hold the request after it captured the generation, until the late publication is observed.
                every { fixture.compilation.beginImplementationQuery() } answers {
                    callOriginal().also {
                        acquiring.countDown()
                        check(proceed.await(10, TimeUnit.SECONDS)) { "test never released the request" }
                    }
                }
                val request = async(Dispatchers.Default) {
                    fixture.handler.getImplementations(fixture.contract.toString(), Position(1, 12))
                }
                assertTrue(
                    acquiring.await(10, TimeUnit.SECONDS),
                    "Request must capture the pre-invalidation generation",
                )
                fixture.compilation.clearCaches()
                release.countDown()
                // Invalidation retires the paused compile (#1184): it is cancelled and can no longer publish
                // its cache/symbol facts without the matching semantic document.
                assertFailsWith<CancellationException> { background.await() }
                assertNull(fixture.compilation.getSymbolStorage(document))
                assertNull(fixture.compilation.getWorkspaceSymbolIndex()?.getDocument(document))
                proceed.countDown()

                assertEquals(listOf(fixture.implementation.toString()), request.await().left.map { it.uri })
            }
        } finally {
            release.countDown()
            proceed.countDown()
            unmockkConstructor(InProcessWorkerSession::class)
        }
    }

    private fun pauseParsedDocument(
        armed: AtomicBoolean,
        target: AtomicReference<URI?>,
        parsed: CountDownLatch,
        release: CountDownLatch,
    ) {
        every { anyConstructed<InProcessWorkerSession>().parse(any()) } answers {
            val result = callOriginal()
            if (firstArg<ParseRequest>().uri == target.get() && armed.compareAndSet(true, false)) {
                parsed.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Target parse was not released" }
            }
            result
        }
    }

    @Test
    fun `an unrelated malformed buffer does not block navigation`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.documents.put(URI.create("file:///acquisition/Broken.groovy"), "class Broken {", 1)

            val locations = fixture.handler.getImplementations(fixture.contract.toString(), Position(1, 12)).left

            assertEquals(listOf(fixture.implementation.toString()), locations.map { it.uri })
            coVerify(exactly = 1) { fixture.compilation.ensureCompiled(fixture.contract) }
        }
    }

    @Test
    fun `an unparseable replacement buffer cannot retain old implementation facts`(): Unit = runBlocking {
        fixture().use { fixture ->
            assertEquals(1, fixture.handler.getImplementations(fixture.contract.toString(), Position(1, 12)).left.size)
            fixture.documents.put(fixture.implementation, "class Unrelated {", 2)

            val locations = fixture.handler.getImplementations(fixture.contract.toString(), Position(1, 12)).left

            assertTrue(locations.isEmpty(), "The old implementation must not survive unrelated malformed text")
            coVerify(exactly = 2) { fixture.compilation.ensureCompiled(fixture.contract) }
        }
    }

    @Test
    fun `current buffer text replaces a cached implementation semantic range`(): Unit = runBlocking {
        fixture().use { fixture ->
            val text = checkNotNull(fixture.documents.get(fixture.implementation))
            fixture.documents.put(fixture.implementation, "\n\n$text", 2)

            val locations = fixture.handler.getImplementations(fixture.contract.toString(), Position(1, 12)).left

            assertEquals(listOf(fixture.implementation.toString()), locations.map { it.uri })
            assertEquals(listOf(3), locations.map { it.range.start.line })
        }
    }

    @Test
    fun `continuous invalidation returns ContentModified within a bounded amount of work`(): Unit = runBlocking {
        fixture().use { fixture ->
            val invalidations = fixture.invalidateAfterCompilation(repeatedly = true)

            val error = assertFailsWith<ResponseErrorException> {
                fixture.handler.getImplementations(fixture.contract.toString(), Position(0, 11))
            }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            assertTrue(invalidations.get() in 1..3, "Invalidations: ${invalidations.get()}")
        }
    }

    @Test
    fun `the public implementation endpoint preserves ContentModified on its future`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                fixture.invalidateAfterCompilation(repeatedly = true)
                val service = GroovyTextDocumentService(
                    this,
                    fixture.compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = fixture.documents),
                )

                val error = assertFailsWith<ResponseErrorException> {
                    service.implementation(
                        ImplementationParams(TextDocumentIdentifier(fixture.contract.toString()), Position(0, 11)),
                    ).await()
                }

                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `a stable empty result is returned without retrying`(): Unit = runBlocking {
        fixture(implementsContract = false).use { fixture ->
            assertTrue(fixture.handler.getImplementations(fixture.contract.toString(), Position(0, 11)).left.isEmpty())
            coVerify(exactly = 1) { fixture.compilation.ensureCompiled(fixture.contract) }
        }
    }

    @Test
    fun `cancellation propagates without a second acquisition attempt`(): Unit = runBlocking {
        fixture().use { fixture ->
            coEvery { fixture.compilation.ensureCompiled(fixture.contract) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                fixture.handler.getImplementations(fixture.contract.toString(), Position(0, 11))
            }

            coVerify(exactly = 1) { fixture.compilation.ensureCompiled(fixture.contract) }
        }
    }

    private suspend fun CoroutineScope.assertSurvivesInvalidation(
        position: Position,
        expectedLine: Int,
        invalidatedDocument: (Fixture) -> URI? = { null },
    ) {
        fixture().use { fixture ->
            assertEquals(1, fixture.handler.getImplementations(fixture.contract.toString(), position).left.size)
            val invalidations = fixture.invalidateAfterCompilation(uri = invalidatedDocument(fixture))

            val locations = fixture.handler.getImplementations(fixture.contract.toString(), position).left

            assertEquals(1, invalidations.get(), "The invalidation must occur at the forced acquisition boundary")
            assertEquals(listOf(fixture.implementation.toString()), locations.map { it.uri })
            assertEquals(listOf(expectedLine), locations.map { it.range.start.line })
        }
    }

    private suspend fun CoroutineScope.fixture(
        implementsContract: Boolean = true,
        closedOrigin: Boolean = false,
    ): Fixture {
        val contract = if (closedOrigin) {
            workspace.resolve("Contract.groovy").toUri()
        } else {
            URI.create("file:///acquisition/Contract.groovy")
        }
        val implementation = if (closedOrigin) {
            workspace.resolve("Impl.groovy").toUri()
        } else {
            URI.create("file:///acquisition/Impl.groovy")
        }
        val contractText = "interface Contract {\n    String greet()\n}\n"
        val declaration = if (implementsContract) "class Impl implements Contract" else "class Unrelated"
        val implementationText = "$declaration {\n    String greet() { 'hi' }\n}\n"
        val documents = DocumentProvider().apply {
            put(contract, contractText, 1)
            put(implementation, implementationText, 1)
        }
        val compilation = GroovyCompilationService(documentProvider = documents)
        if (closedOrigin) {
            Files.writeString(Path.of(contract), contractText)
            Files.writeString(Path.of(implementation), implementationText)
            compilation.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
        }
        assertTrue(compilation.compile(contract, contractText).isSuccess)
        assertTrue(compilation.compile(implementation, implementationText).isSuccess)
        return Fixture(contract, implementation, compilation, documents) { observed ->
            CrossFileOperationsHandler(
                observed,
                documents,
                mockk<SourceNavigator>(),
                { false },
                {},
            )
        }
    }

    private class Fixture(
        val contract: URI,
        val implementation: URI,
        private val actual: GroovyCompilationService,
        val documents: DocumentProvider,
        createHandler: (GroovyCompilationService) -> CrossFileOperationsHandler,
    ) : AutoCloseable {
        val compilation = spyk(actual)
        val handler = createHandler(compilation)

        init {
            assertSame(actual.compilationCacheService, compilation.compilationCacheService)
            assertSame(actual.symbolIndexingService, compilation.symbolIndexingService)
        }

        fun invalidateAfterCompilation(repeatedly: Boolean = false, uri: URI? = null): AtomicInteger {
            val armed = AtomicBoolean(true)
            val invalidations = AtomicInteger()
            coEvery { compilation.ensureCompiled(contract) } coAnswers {
                // Await the real delegate; suspend-spy callOriginal returns the suspension marker.
                val result = actual.ensureCompiled(contract)
                if (repeatedly || armed.compareAndSet(true, false)) {
                    if (uri == null) compilation.clearCaches() else compilation.invalidateCache(uri)
                    invalidations.incrementAndGet()
                }
                result
            }

            return invalidations
        }

        override fun close() = compilation.clearCaches()
    }
}
