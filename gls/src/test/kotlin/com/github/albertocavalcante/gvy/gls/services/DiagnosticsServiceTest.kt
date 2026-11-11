package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticProvider
import com.github.albertocavalcante.gvy.gls.config.DiagnosticConfig
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.DiagnosticProviderAdapter
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val COORDINATED_TEST_TIMEOUT_MS = 10_000L

class DiagnosticsServiceTest {

    // ==================== Basic Functionality Tests ====================

    @Test
    fun `returns diagnostics from single enabled provider`(): Unit = runBlocking {
        // Given
        val diagnostic = createTestDiagnostic("Test diagnostic", 1)
        val provider = TestStreamingDiagnosticProvider(
            id = "test-provider",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(diagnostic),
        )
        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(1, result.size)
        assertEquals("Test diagnostic", result[0].message)
    }

    @Test
    fun `returns combined diagnostics from multiple enabled providers`(): Unit = runBlocking {
        // Given
        val diagnostic1 = createTestDiagnostic("Diagnostic 1", 1)
        val diagnostic2 = createTestDiagnostic("Diagnostic 2", 2)
        val diagnostic3 = createTestDiagnostic("Diagnostic 3", 3)
        val diagnostic4 = createTestDiagnostic("Diagnostic 4", 4)

        val provider1 = TestStreamingDiagnosticProvider(
            id = "provider1",
            diagnosticsToEmit = listOf(diagnostic1, diagnostic2),
        )
        val provider2 = TestStreamingDiagnosticProvider(
            id = "provider2",
            diagnosticsToEmit = listOf(diagnostic3, diagnostic4),
        )

        val service = DiagnosticsService(
            providers = listOf(provider1, provider2),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(4, result.size)
        assertTrue(result.any { it.message == "Diagnostic 1" })
        assertTrue(result.any { it.message == "Diagnostic 2" })
        assertTrue(result.any { it.message == "Diagnostic 3" })
        assertTrue(result.any { it.message == "Diagnostic 4" })
    }

    @Test
    fun `returns empty list when no providers are configured`(): Unit = runBlocking {
        // Given
        val service = DiagnosticsService(
            providers = emptyList(),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.isEmpty())
    }

    // ==================== Configuration Filtering Tests ====================

    @Test
    fun `respects disabled providers and skips disabled provider`(): Unit = runBlocking {
        // Given
        val diagnostic = createTestDiagnostic("Should not appear", 1)
        val provider = TestStreamingDiagnosticProvider(
            id = "test-provider",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(diagnostic),
        )

        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(disabledProviders = setOf("test-provider")),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.isEmpty(), "Denied provider should not emit diagnostics")
    }

    @Test
    fun `respects enabled providers and enables disabled-by-default provider`(): Unit = runBlocking {
        // Given
        val diagnostic = createTestDiagnostic("Should appear", 1)
        val provider = TestStreamingDiagnosticProvider(
            id = "slow-provider",
            enabledByDefault = false,
            diagnosticsToEmit = listOf(diagnostic),
        )

        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(enabledProviders = setOf("slow-provider")),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(1, result.size)
        assertEquals("Should appear", result[0].message)
    }

    @Test
    fun `filters out multiple disabled providers`(): Unit = runBlocking {
        // Given
        val enabledProvider1 = TestStreamingDiagnosticProvider(
            id = "enabled1",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(createTestDiagnostic("Enabled 1", 1)),
        )
        val disabledProvider = TestStreamingDiagnosticProvider(
            id = "disabled1",
            enabledByDefault = false,
            diagnosticsToEmit = listOf(createTestDiagnostic("Should not appear", 2)),
        )
        val enabledProvider2 = TestStreamingDiagnosticProvider(
            id = "enabled2",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(createTestDiagnostic("Enabled 2", 3)),
        )

        val service = DiagnosticsService(
            providers = listOf(enabledProvider1, disabledProvider, enabledProvider2),
            config = DiagnosticConfig(), // No enabled providers
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(2, result.size)
        assertTrue(result.any { it.message == "Enabled 1" })
        assertTrue(result.any { it.message == "Enabled 2" })
        assertTrue(result.none { it.message == "Should not appear" })
    }

    @Test
    fun `disabled providers take precedence over enabled providers`(): Unit = runBlocking {
        // Given
        val provider = TestStreamingDiagnosticProvider(
            id = "conflicted-provider",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(createTestDiagnostic("Should not appear", 1)),
        )

        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(
                disabledProviders = setOf("conflicted-provider"),
                enabledProviders = setOf("conflicted-provider"),
            ),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.isEmpty(), "Disabled providers should take precedence over enabled providers")
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `continues processing when one provider throws exception`(): Unit = runBlocking {
        // Given
        val workingProvider1 = TestStreamingDiagnosticProvider(
            id = "working1",
            diagnosticsToEmit = listOf(createTestDiagnostic("Working 1", 1)),
        )
        val failingProvider = TestStreamingDiagnosticProvider(
            id = "failing",
            shouldFail = true,
        )
        val workingProvider2 = TestStreamingDiagnosticProvider(
            id = "working2",
            diagnosticsToEmit = listOf(createTestDiagnostic("Working 2", 2)),
        )

        val service = DiagnosticsService(
            providers = listOf(workingProvider1, failingProvider, workingProvider2),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(2, result.size, "Should collect diagnostics from working providers")
        assertTrue(result.any { it.message == "Working 1" })
        assertTrue(result.any { it.message == "Working 2" })
    }

    @Test
    fun `handles provider that emits some diagnostics then fails`(): Unit = runBlocking {
        // Given
        val partialProvider = TestStreamingDiagnosticProvider(
            id = "partial",
            diagnosticsToEmit = listOf(
                createTestDiagnostic("Before failure", 1),
                createTestDiagnostic("Also before failure", 2),
            ),
            shouldFailAfterEmitting = true,
        )

        val service = DiagnosticsService(
            providers = listOf(partialProvider),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(2, result.size, "Should collect diagnostics emitted before failure")
        assertTrue(result.any { it.message == "Before failure" })
        assertTrue(result.any { it.message == "Also before failure" })
    }

    @Test
    fun `records provider ID when catching exceptions`(): Unit = runBlocking {
        // Given
        val failingProvider = TestStreamingDiagnosticProvider(
            id = "failing-provider-with-clear-id",
            shouldFail = true,
        )

        val service = DiagnosticsService(
            providers = listOf(failingProvider),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.collectDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(listOf("failing-provider-with-clear-id"), result.failures.map { it.providerId })
        assertFalse(result.isSuccessful)
    }

    @Test
    fun `propagates CancellationException when coroutine is cancelled`(): Unit = runBlocking {
        // Given: A slow provider that we can cancel mid-execution
        val slowProvider = TestStreamingDiagnosticProvider(
            id = "slow-provider",
            diagnosticsToEmit = listOf(createTestDiagnostic("Will never emit", 1)),
            delayBeforeEmit = 1000, // 1 second delay
        )

        val service = DiagnosticsService(
            providers = listOf(slowProvider),
            config = DiagnosticConfig(),
        )

        // When: We cancel the coroutine mid-execution using withTimeout
        val exception = kotlin.runCatching {
            kotlinx.coroutines.withTimeout(50) {
                // Cancel after 50ms
                service.getDiagnostics(testUri, "test content")
            }
        }.exceptionOrNull()

        // Then: Verify that cancellation exception was thrown
        assertTrue(
            exception is kotlinx.coroutines.TimeoutCancellationException,
            "Timeout should cause CancellationException, got: ${exception?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `provider cancellation aborts collection`(): Unit = runBlocking {
        val provider = object : StreamingDiagnosticProvider {
            override val id = "cancelled-provider"

            override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
                throw CancellationException("Provider aborted")
            }
        }
        val service = DiagnosticsService(listOf(provider))

        val failure = assertFailsWith<CancellationException> {
            service.getDiagnostics(testUri, "test content")
        }
        assertEquals("Provider aborted", failure.message)
    }

    @Nested
    inner class CollectionOutcomes {
        @Test
        fun `empty provider cohort completes successfully`() = runBlocking {
            val result = DiagnosticsService(emptyList()).collectDiagnostics(testUri, "test content")

            assertTrue(result.diagnostics.isEmpty())
            assertTrue(result.providerIds.isEmpty())
            assertTrue(result.failures.isEmpty())
            assertTrue(result.isSuccessful)
        }

        @Test
        fun `enabled cohort order is independent of diagnostic arrival order`() = runBoundedTest {
            val secondEmitted = CompletableDeferred<Unit>()
            val first = streamingProvider("first") {
                secondEmitted.await()
                emit(createTestDiagnostic("First provider"))
            }
            val second = streamingProvider("second") {
                emit(createTestDiagnostic("Second provider"))
                secondEmitted.complete(Unit)
            }
            val disabled = TestStreamingDiagnosticProvider("disabled", enabledByDefault = false, shouldFail = true)

            val result = DiagnosticsService(listOf(first, disabled, second)).collectDiagnostics(testUri, "test content")

            assertEquals(listOf("first", "second"), result.providerIds)
            assertEquals(listOf("Second provider", "First provider"), result.diagnostics.map { it.message })
            assertTrue(result.failures.isEmpty())
            assertTrue(result.isSuccessful)
        }

        @Test
        fun `partial failures retain their diagnostics and healthy peers`() = runBlocking {
            val partial = TestStreamingDiagnosticProvider(
                id = "partial",
                diagnosticsToEmit = listOf(createTestDiagnostic("Before failure")),
                shouldFailAfterEmitting = true,
            )
            val healthy = streamingProvider("healthy") { emit(createTestDiagnostic("Healthy")) }

            val result = DiagnosticsService(listOf(partial, healthy)).collectDiagnostics(testUri, "test content")

            assertEquals(setOf("Before failure", "Healthy"), result.diagnostics.map { it.message }.toSet())
            assertEquals(listOf("partial", "healthy"), result.providerIds)
            assertEquals(
                listOf(
                    DiagnosticProviderFailure(
                        providerId = "partial",
                        exceptionType = RuntimeException::class.java.name,
                        message = "Provider partial failed after emitting diagnostics",
                    ),
                ),
                result.failures,
            )
            assertFalse(result.isSuccessful)
        }

        @Test
        fun `failure before flow creation records type and nullable message`() = runBlocking {
            val provider = object : StreamingDiagnosticProvider {
                override val id = "before-flow"

                override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> =
                    throw IllegalArgumentException()
            }

            val result = DiagnosticsService(listOf(provider)).collectDiagnostics(testUri, "test content")

            assertTrue(result.diagnostics.isEmpty())
            assertEquals(
                listOf(DiagnosticProviderFailure("before-flow", IllegalArgumentException::class.java.name, null)),
                result.failures,
            )
            assertFalse(result.isSuccessful)
        }

        @Test
        fun `checked provider exceptions retain healthy diagnostics`() = runBlocking {
            val failing = streamingProvider("io") { throw IOException("Provider read failed") }
            val healthy = streamingProvider("healthy") { emit(createTestDiagnostic("Healthy")) }

            val result = DiagnosticsService(listOf(failing, healthy)).collectDiagnostics(testUri, "test content")

            assertEquals(listOf("Healthy"), result.diagnostics.map { it.message })
            assertEquals(
                DiagnosticProviderFailure("io", IOException::class.java.name, "Provider read failed"),
                result.failures.single(),
            )
            assertFalse(result.isSuccessful)
        }

        @Test
        fun `failures follow registration order after reverse completion`() = runBoundedTest {
            val secondFinished = CompletableDeferred<Unit>()
            val first = streamingProvider("first") {
                secondFinished.await()
                error("First failed")
            }
            val second = streamingProvider("second") {
                currentCoroutineContext().job.invokeOnCompletion { secondFinished.complete(Unit) }
                error("Second failed")
            }

            val result = DiagnosticsService(listOf(first, second)).collectDiagnostics(testUri, "test content")

            assertEquals(listOf("first", "second"), result.providerIds)
            assertEquals(listOf("first", "second"), result.failures.map { it.providerId })
            assertFalse(result.isSuccessful)
        }

        @Test
        fun `provider cancellation releases an active peer`() = runBoundedTest {
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val waiting = streamingProvider("waiting") {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            val cancelled = streamingProvider("cancelled") {
                started.await()
                throw CancellationException("Provider aborted")
            }

            val failure = assertFailsWith<CancellationException> {
                DiagnosticsService(listOf(waiting, cancelled)).collectDiagnostics(testUri, "test content")
            }

            assertEquals("Provider aborted", failure.message)
            assertTrue(released.isCompleted)
        }

        @Test
        fun `fatal provider errors propagate`() = runBlocking {
            val fatal = streamingProvider("fatal") { throw LinkageError("Broken provider") }

            val failure = assertFailsWith<LinkageError> {
                DiagnosticsService(listOf(fatal)).collectDiagnostics(testUri, "test content")
            }

            assertEquals("Broken provider", failure.message)
        }

        @Test
        fun `a provider cancelling its own coroutine cannot report success`() = runBlocking {
            val cancelled = streamingProvider("self-cancelled") {
                currentCoroutineContext().cancel(CancellationException("Provider stopped"))
            }

            val failure = assertFailsWith<CancellationException> {
                DiagnosticsService(listOf(cancelled)).collectDiagnostics(testUri, "test content")
            }

            assertEquals("Provider stopped", failure.message)
        }

        private fun <T> runBoundedTest(block: suspend CoroutineScope.() -> T): T = runBlocking {
            withTimeout(COORDINATED_TEST_TIMEOUT_MS, block)
        }

        private fun streamingProvider(
            id: String,
            block: suspend FlowCollector<Diagnostic>.() -> Unit,
        ): StreamingDiagnosticProvider = object : StreamingDiagnosticProvider {
            override val id = id

            override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow(block)
        }
    }

    // ==================== Integration Tests ====================

    @Test
    fun `works with DiagnosticProviderAdapter wrapping mock legacy provider`(): Unit = runBlocking {
        // Given
        val diagnostic = createTestDiagnostic("Legacy diagnostic", 1)
        val legacyProvider = TestLegacyDiagnosticProvider(listOf(diagnostic))
        val adapter = DiagnosticProviderAdapter(
            delegate = legacyProvider,
            id = "legacy-adapter",
            enabledByDefault = true,
        )

        val service = DiagnosticsService(
            providers = listOf(adapter),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(1, result.size)
        assertEquals("Legacy diagnostic", result[0].message)
    }

    @Test
    fun `adapts a legacy provider registered as CodeNarc`(): Unit = runBlocking {
        val groovySource = """
            class Example {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        // Check the adapter and CodeNarc provider ID using a legacy provider test double.
        val mockDiagnostic = createTestDiagnostic("TrailingWhitespace", 2)
        val mockCodeNarc = TestLegacyDiagnosticProvider(listOf(mockDiagnostic))
        val adapter = DiagnosticProviderAdapter(
            delegate = mockCodeNarc,
            id = "codenarc",
            enabledByDefault = true,
        )

        val service = DiagnosticsService(
            providers = listOf(adapter),
            config = DiagnosticConfig(),
        )

        // When
        val result = service.getDiagnostics(testUri, groovySource)

        // Then
        assertEquals(1, result.size)
        assertEquals("TrailingWhitespace", result[0].message)
    }

    // ==================== Test Helpers ====================

    private val testUri = URI.create("file:///test/TestFile.groovy")

    private fun createTestDiagnostic(message: String, line: Int = 0): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, 0), Position(line, 10))
        severity = DiagnosticSeverity.Warning
        this.message = message
        source = "Test"
    }

    // Test implementation of StreamingDiagnosticProvider
    @Suppress("TooGenericExceptionThrown") // RuntimeException is intentional for testing error handling
    private class TestStreamingDiagnosticProvider(
        override val id: String,
        override val enabledByDefault: Boolean = true,
        private val diagnosticsToEmit: List<Diagnostic> = emptyList(),
        private val shouldFail: Boolean = false,
        private val shouldFailAfterEmitting: Boolean = false,
        private val delayBeforeEmit: Long = 0,
    ) : StreamingDiagnosticProvider {
        override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
            if (shouldFail) {
                throw RuntimeException("Provider $id failed")
            }

            if (delayBeforeEmit > 0) {
                kotlinx.coroutines.delay(delayBeforeEmit)
            }

            diagnosticsToEmit.forEach { emit(it) }

            if (shouldFailAfterEmitting) {
                throw RuntimeException("Provider $id failed after emitting diagnostics")
            }
        }
    }

    // Test implementation of legacy DiagnosticProvider
    private class TestLegacyDiagnosticProvider(private val diagnostics: List<Diagnostic>) : DiagnosticProvider {
        override suspend fun analyze(source: String, uri: URI): List<Diagnostic> = diagnostics
    }
}
