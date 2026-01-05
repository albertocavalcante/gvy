package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.providers.diagnostics.StreamingDiagnosticProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.eclipse.lsp4j.Diagnostic
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticConfigTest {

    @Test
    fun `provider enabled by default when not disabled`() {
        val config = DiagnosticConfig(disabledProviders = emptySet())
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertTrue(config.isProviderEnabled(provider))
    }

    @Test
    fun `provider disabled when in disabled providers`() {
        val config = DiagnosticConfig(disabledProviders = setOf("test-provider"))
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `provider enabled when in enabled providers even if disabled by default`() {
        val config = DiagnosticConfig(
            disabledProviders = emptySet(),
            enabledProviders = setOf("test-provider"),
        )
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = false)

        assertTrue(config.isProviderEnabled(provider))
    }

    @Test
    fun `provider disabled by default when not enabled`() {
        val config = DiagnosticConfig(disabledProviders = emptySet(), enabledProviders = emptySet())
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = false)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `disabled providers take precedence over enabled providers`() {
        val config = DiagnosticConfig(
            disabledProviders = setOf("test-provider"),
            enabledProviders = setOf("test-provider"),
        )
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `disabled providers take precedence over enabledByDefault`() {
        val config = DiagnosticConfig(disabledProviders = setOf("test-provider"))
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `enabled providers override enabledByDefault=false`() {
        val config = DiagnosticConfig(
            disabledProviders = emptySet(),
            enabledProviders = setOf("disabled-provider"),
        )
        val provider = TestStreamingDiagnosticProvider(id = "disabled-provider", enabledByDefault = false)

        assertTrue(config.isProviderEnabled(provider))
    }

    // Test helper
    private class TestStreamingDiagnosticProvider(override val id: String, override val enabledByDefault: Boolean) :
        StreamingDiagnosticProvider {
        override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = emptyFlow()
    }
}
