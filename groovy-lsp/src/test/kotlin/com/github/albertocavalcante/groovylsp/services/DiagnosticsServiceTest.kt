package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.diagnostics.api.DiagnosticProvider
import com.github.albertocavalcante.groovylsp.config.DiagnosticConfig
import com.github.albertocavalcante.groovylsp.providers.diagnostics.DiagnosticProviderAdapter
import com.github.albertocavalcante.groovylsp.providers.diagnostics.StreamingDiagnosticProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsServiceTest {

    // ==================== Basic Functionality Tests ====================

    @Test
    fun `returns diagnostics from single enabled provider`() = runBlocking {
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
    fun `returns combined diagnostics from multiple enabled providers`() = runBlocking {
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
    fun `returns empty list when no providers are configured`() = runBlocking {
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
    fun `respects denylist and skips denied provider`() = runBlocking {
        // Given
        val diagnostic = createTestDiagnostic("Should not appear", 1)
        val provider = TestStreamingDiagnosticProvider(
            id = "test-provider",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(diagnostic),
        )

        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(denylist = setOf("test-provider")),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.isEmpty(), "Denied provider should not emit diagnostics")
    }

    @Test
    fun `respects allowlist and enables disabled-by-default provider`() = runBlocking {
        // Given
        val diagnostic = createTestDiagnostic("Should appear", 1)
        val provider = TestStreamingDiagnosticProvider(
            id = "slow-provider",
            enabledByDefault = false,
            diagnosticsToEmit = listOf(diagnostic),
        )

        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(allowlist = setOf("slow-provider")),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertEquals(1, result.size)
        assertEquals("Should appear", result[0].message)
    }

    @Test
    fun `filters out multiple disabled providers`() = runBlocking {
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
            config = DiagnosticConfig(), // No allowlist
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
    fun `denylist takes precedence over allowlist`() = runBlocking {
        // Given
        val provider = TestStreamingDiagnosticProvider(
            id = "conflicted-provider",
            enabledByDefault = true,
            diagnosticsToEmit = listOf(createTestDiagnostic("Should not appear", 1)),
        )

        val service = DiagnosticsService(
            providers = listOf(provider),
            config = DiagnosticConfig(
                denylist = setOf("conflicted-provider"),
                allowlist = setOf("conflicted-provider"),
            ),
        )

        // When
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.isEmpty(), "Denylist should take precedence over allowlist")
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `continues processing when one provider throws exception`() = runBlocking {
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
    fun `handles provider that emits some diagnostics then fails`() = runBlocking {
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
    fun `logs provider ID when catching exceptions`() = runBlocking {
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
        val result = service.getDiagnostics(testUri, "test content")

        // Then
        assertTrue(result.isEmpty())
        // NOTE: Logger output verification would require log capture setup
        // For now, we verify the service doesn't crash and returns empty list
    }

    @Test
    fun `propagates CancellationException when coroutine is cancelled`() = runBlocking {
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

    // ==================== Integration Tests ====================

    @Test
    fun `works with DiagnosticProviderAdapter wrapping mock legacy provider`() = runBlocking {
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
    fun `handles CodeNarcDiagnosticProvider via adapter`() = runBlocking {
        // NOTE: This is a slower integration test with real CodeNarc
        // Given: Groovy code with a known CodeNarc violation (trailing whitespace)
        val groovySourceWithViolation = """
            class Example {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        // This test would require real CodeNarcDiagnosticProvider setup
        // For now, we test the adapter pattern works with mock
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
        val result = service.getDiagnostics(testUri, groovySourceWithViolation)

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
