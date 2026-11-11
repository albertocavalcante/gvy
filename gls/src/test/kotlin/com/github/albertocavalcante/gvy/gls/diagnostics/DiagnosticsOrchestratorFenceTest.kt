package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsOrchestratorFenceTest {
    private class Fixture(scope: CoroutineScope, configuration: ServerConfiguration = ServerConfiguration()) {
        val uri: URI = URI.create("file:///workspace/Fence.groovy")
        val content = "class Fence {}"
        val documents = DocumentProvider().apply { put(uri, content, INITIAL_VERSION) }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>()
        val orchestrator: DiagnosticsOrchestrator

        init {
            val parsed = mockk<CompilationResult>()
            every { parsed.diagnostics } returns emptyList()
            every { parsed.parseCompleted } returns true
            coEvery { compilation.compileAsync(any(), uri, any()) } returns CompletableDeferred(parsed)
            coEvery { providers.collectDiagnostics(uri, any()) } returns healthyResult()
            every { client.publishDiagnostics(capture(publications)) } just Runs
            orchestrator = DiagnosticsOrchestrator(
                scope,
                compilation,
                providers,
                documents,
                configuration,
                { client },
                debounceMillis = 0,
            )
        }

        fun trigger() {
            orchestrator.trigger(uri, checkNotNull(documents.get(uri)))
        }
    }

    @Test
    fun `an empty parser publication cannot complete a fence while providers are running`(): Unit = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<DiagnosticsCollectionResult>()
        coEvery { fixture.providers.collectDiagnostics(any(), any()) } coAnswers { release.await() }
        fixture.trigger()
        runCurrent()
        assertTrue(fixture.publications.single().diagnostics.isEmpty())

        val fence = async { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
        runCurrent()
        assertFalse(fence.isCompleted)
        release.complete(healthyResult())
        advanceUntilIdle()

        assertTrue(fence.await().diagnostics.isEmpty())
        assertTrue(fence.await().failures.isEmpty())
        assertEquals(listOf("parser", PROVIDER_ID), fence.await().providerIds)
        assertEquals(INITIAL_VERSION, fence.await().version)
    }

    @Test
    fun `completed results remain observable without another compilation`(): Unit = runTest {
        val fixture = Fixture(this)
        fixture.trigger()
        advanceUntilIdle()
        assertNull(fixture.orchestrator.getDiagnosticJob(fixture.uri))

        val first = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        val second = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        assertEquals(first.runId, second.runId)
        assertEquals(fixture.uri.toString(), first.uri)
        coVerify(exactly = 1) { fixture.compilation.compileAsync(any(), fixture.uri, fixture.content) }
        coVerify(exactly = 1) { fixture.providers.collectDiagnostics(fixture.uri, fixture.content) }
    }

    @Test
    fun `a fence retains partial provider failure metadata`(): Unit = runTest {
        val fixture = Fixture(this)
        val partial = Diagnostic().apply { message = "partial" }
        val failure = DiagnosticProviderFailure(PROVIDER_ID, IOException::class.java.name, "unavailable")
        coEvery { fixture.providers.collectDiagnostics(any(), any()) } returns DiagnosticsCollectionResult(
            listOf(partial),
            listOf(PROVIDER_ID),
            listOf(failure),
        )
        fixture.trigger()
        advanceUntilIdle()

        val result = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        assertEquals(listOf(partial), result.diagnostics)
        assertEquals(listOf(failure), result.failures)
        assertEquals(fixture.publications.last().diagnostics, result.diagnostics)
    }

    @Test
    fun `an infrastructure exception remains observable after the job is removed`(): Unit = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.providers.collectDiagnostics(any(), any()) } throws IOException("collector failed")
        fixture.trigger()
        advanceUntilIdle()
        assertNull(fixture.orchestrator.getDiagnosticJob(fixture.uri))

        val failure = assertFailsWith<IOException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        }
        assertEquals("collector failed", failure.message)
    }

    @Test
    fun `cancelling one waiter preserves shared analysis and other waiters`(): Unit = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<DiagnosticsCollectionResult>()
        coEvery { fixture.providers.collectDiagnostics(any(), any()) } coAnswers { release.await() }
        fixture.trigger()
        val first = async { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
        val second = async { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
        runCurrent()
        first.cancelAndJoin()
        assertTrue(checkNotNull(fixture.orchestrator.getDiagnosticJob(fixture.uri)).isActive)
        assertFalse(second.isCompleted)

        release.complete(healthyResult())
        advanceUntilIdle()
        assertTrue(second.await().failures.isEmpty())
        coVerify(exactly = 1) { fixture.providers.collectDiagnostics(any(), any()) }
    }

    @Test
    fun `an accepted edit retires both waiting and completed results`(): Unit = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<DiagnosticsCollectionResult>()
        coEvery { fixture.providers.collectDiagnostics(any(), fixture.content) } coAnswers { release.await() }
        fixture.trigger()
        val waiting = async {
            runCatching { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
        }
        runCurrent()
        fixture.documents.update(fixture.uri, "class Changed {}", INITIAL_VERSION + 1)
        fixture.trigger()
        advanceUntilIdle()

        assertIs<DiagnosticsRunUnavailableException>(waiting.await().exceptionOrNull())
        val current = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION + 1)
        assertEquals(INITIAL_VERSION + 1, current.version)
        fixture.documents.update(fixture.uri, "class Latest {}", INITIAL_VERSION + 2)
        assertFailsWith<DiagnosticsRunUnavailableException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION + 2)
        }
    }

    @Test
    fun `same text and version on reopen cannot inherit an old completed run`(): Unit = runTest {
        val fixture = Fixture(this)
        fixture.trigger()
        advanceUntilIdle()
        val old = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        fixture.orchestrator.close(fixture.uri)
        fixture.documents.put(fixture.uri, fixture.content, INITIAL_VERSION)

        assertFailsWith<DiagnosticsRunUnavailableException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        }
        fixture.trigger()
        advanceUntilIdle()
        assertNotEquals(old.runId, fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION).runId)
    }

    @Test
    fun `configuration replacement invalidates a retained completed result`(): Unit = runTest {
        val fixture = Fixture(this)
        fixture.trigger()
        advanceUntilIdle()
        val old = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        val replacement = mockk<DiagnosticsService>()
        coEvery { replacement.collectDiagnostics(any(), any()) } returns healthyResult()
        fixture.orchestrator.updateConfiguration(ServerConfiguration(maxNumberOfProblems = 1), replacement)
        advanceUntilIdle()

        val current = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        assertNotEquals(old.runId, current.runId)
        assertNotEquals(old.configurationGeneration, current.configurationGeneration)
        coVerify(exactly = 1) { replacement.collectDiagnostics(any(), any()) }
    }

    @Test
    fun `closed documents and mismatched requested versions fail explicitly`(): Unit = runTest {
        val fixture = Fixture(this)
        fixture.trigger()
        advanceUntilIdle()
        assertFailsWith<DiagnosticsRunUnavailableException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION + 1)
        }
        fixture.orchestrator.close(fixture.uri)
        assertFailsWith<DiagnosticsRunUnavailableException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        }
    }

    @Test
    fun `a lazy job cancelled before it starts cannot strand a fence`(): Unit = runTest {
        val fixture = Fixture(this)
        fixture.trigger()
        fixture.orchestrator.cancelAndRemove(fixture.uri)
        advanceUntilIdle()

        assertFailsWith<DiagnosticsRunUnavailableException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        }
        coVerify(exactly = 0) { fixture.compilation.compileAsync(any(), any(), any()) }
    }

    @Test
    fun `an already cancelled owner cannot retain a completed job during registration`(): Unit = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        owner.cancel()
        val fixture = Fixture(owner)
        fixture.trigger()
        runCurrent()

        assertNull(fixture.orchestrator.getDiagnosticJob(fixture.uri))
        assertFailsWith<DiagnosticsRunUnavailableException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        }
        coVerify(exactly = 0) { fixture.compilation.compileAsync(any(), any(), any()) }
    }

    @Test
    fun `an incomplete parser result cannot certify completed diagnostics`(): Unit = runTest {
        assertIncompleteParsingRejected(ServerConfiguration())
    }

    @Test
    fun `disabled parser diagnostics cannot turn incomplete parsing into empty success`(): Unit = runTest {
        val base = ServerConfiguration()
        assertIncompleteParsingRejected(
            base.copy(diagnosticConfig = base.diagnosticConfig.copy(disabledProviders = setOf("parser"))),
        )
    }

    @Test
    fun `a completed syntax error analysis remains a valid terminal result`(): Unit = runTest {
        val fixture = Fixture(this)
        val syntax = Diagnostic().apply { message = "syntax error" }
        val result = CompilationResult(
            isSuccess = false,
            ast = null,
            diagnostics = listOf(syntax),
            sourceText = fixture.content,
            parseCompleted = true,
        )
        coEvery { fixture.compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        fixture.trigger()
        advanceUntilIdle()

        val completed = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        assertEquals(listOf(syntax), completed.diagnostics)
        assertTrue(completed.failures.isEmpty())
        assertTrue(completed.complete)
    }

    private suspend fun TestScope.assertIncompleteParsingRejected(configuration: ServerConfiguration) {
        val fixture = Fixture(this, configuration)
        val failure = CompilationResult.failure(
            listOf(Diagnostic().apply { message = "worker failed" }),
            fixture.content,
        )
        coEvery { fixture.compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(failure)
        fixture.trigger()
        advanceUntilIdle()

        val error = assertFailsWith<IllegalStateException> {
            fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        }
        assertTrue(checkNotNull(error.message).contains("Parsing did not complete"))
        coVerify(exactly = 0) { fixture.providers.collectDiagnostics(any(), any()) }
    }

    @Test
    fun `fatal provider errors reach both the owner and the retained failure`(): Unit = runTest {
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> failures += failure }
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
        try {
            val fixture = Fixture(owner)
            val fatal = AssertionError("provider aborted")
            coEvery { fixture.providers.collectDiagnostics(any(), any()) } throws fatal
            fixture.trigger()
            advanceUntilIdle()

            assertSame(fatal, failures.single())
            val observed = assertFailsWith<AssertionError> {
                fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
            }
            assertEquals(fatal.message, observed.message)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `problem limits cannot hide a provider failure from the terminal result`(): Unit = runTest {
        val fixture = Fixture(this, ServerConfiguration(maxNumberOfProblems = 0))
        val failure = DiagnosticProviderFailure(PROVIDER_ID, IOException::class.java.name, "failed")
        coEvery { fixture.providers.collectDiagnostics(any(), any()) } returns DiagnosticsCollectionResult(
            listOf(Diagnostic().apply { message = "partial" }),
            listOf(PROVIDER_ID),
            listOf(failure),
        )
        fixture.trigger()
        advanceUntilIdle()

        val result = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(fixture.publications.all { it.diagnostics.isEmpty() })
        assertEquals(listOf(failure), result.failures)
    }

    @Nested
    inner class InvalidationDuringProviders {
        @Test
        fun `configuration replacement releases a waiter before the retired provider returns`(): Unit = runTest {
            val fixture = Fixture(this)
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.providers.collectDiagnostics(any(), any()) } coAnswers {
                withContext(NonCancellable) { release.await() }
                healthyResult()
            }
            fixture.trigger()
            val waiting = async {
                runCatching { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
            }
            runCurrent()
            val previous = checkNotNull(fixture.orchestrator.getDiagnosticJob(fixture.uri))
            try {
                val replacement = mockk<DiagnosticsService>()
                coEvery { replacement.collectDiagnostics(any(), any()) } returns healthyResult()
                fixture.orchestrator.updateConfiguration(ServerConfiguration(), replacement)
                runCurrent()

                assertTrue(waiting.isCompleted, "retirement must wake the fence before provider completion")
                assertIs<DiagnosticsRunUnavailableException>(waiting.await().exceptionOrNull())
                assertFalse(previous.isCompleted)
                val current = fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION)
                assertTrue(current.failures.isEmpty())
                coVerify(exactly = 1) { replacement.collectDiagnostics(any(), any()) }
            } finally {
                release.complete(Unit)
            }
            advanceUntilIdle()
        }

        @Test
        fun `identical version reopen releases a waiter before the retired provider returns`(): Unit = runTest {
            val fixture = Fixture(this)
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.providers.collectDiagnostics(any(), any()) } coAnswers {
                withContext(NonCancellable) { release.await() }
                healthyResult()
            }
            fixture.trigger()
            val waiting = async {
                runCatching { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
            }
            runCurrent()
            val previous = checkNotNull(fixture.orchestrator.getDiagnosticJob(fixture.uri))
            try {
                fixture.orchestrator.close(fixture.uri)
                fixture.documents.put(fixture.uri, fixture.content, INITIAL_VERSION)
                coEvery { fixture.providers.collectDiagnostics(any(), any()) } returns healthyResult()
                fixture.trigger()
                runCurrent()

                assertTrue(waiting.isCompleted, "reopen must retire the old result despite identical version and text")
                assertIs<DiagnosticsRunUnavailableException>(waiting.await().exceptionOrNull())
                assertFalse(previous.isCompleted)
                assertEquals(
                    INITIAL_VERSION,
                    fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION).version,
                )
            } finally {
                release.complete(Unit)
            }
            advanceUntilIdle()
        }

        @Test
        fun `an empty provider result revalidates the snapshot without a second publication`(): Unit = runTest {
            val fixture = Fixture(this)
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.providers.collectDiagnostics(any(), any()) } coAnswers {
                release.await()
                fixture.documents.update(fixture.uri, "class Changed {}", INITIAL_VERSION + 1)
                healthyResult()
            }
            fixture.trigger()
            val waiting = async {
                runCatching { fixture.orchestrator.awaitCurrentDiagnostics(fixture.uri, INITIAL_VERSION) }
            }
            runCurrent()
            assertFalse(waiting.isCompleted)
            release.complete(Unit)
            advanceUntilIdle()

            assertIs<DiagnosticsRunUnavailableException>(waiting.await().exceptionOrNull())
            assertEquals(1, fixture.publications.size)
            assertTrue(fixture.publications.single().diagnostics.isEmpty())
        }
    }

    private companion object {
        const val INITIAL_VERSION = 7
        const val PROVIDER_ID = "test-provider"

        fun healthyResult() = DiagnosticsCollectionResult(emptyList(), listOf(PROVIDER_ID), emptyList())
    }
}
