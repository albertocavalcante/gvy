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
    fun `provider enabled by default when not in denylist`() {
        val config = DiagnosticConfig(denylist = emptySet())
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertTrue(config.isProviderEnabled(provider))
    }

    @Test
    fun `provider disabled when in denylist`() {
        val config = DiagnosticConfig(denylist = setOf("test-provider"))
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `provider enabled when in allowlist even if disabled by default`() {
        val config = DiagnosticConfig(
            denylist = emptySet(),
            allowlist = setOf("test-provider"),
        )
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = false)

        assertTrue(config.isProviderEnabled(provider))
    }

    @Test
    fun `provider disabled by default when not in allowlist`() {
        val config = DiagnosticConfig(denylist = emptySet(), allowlist = emptySet())
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = false)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `denylist takes precedence over allowlist`() {
        val config = DiagnosticConfig(
            denylist = setOf("test-provider"),
            allowlist = setOf("test-provider"),
        )
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `denylist takes precedence over enabledByDefault`() {
        val config = DiagnosticConfig(denylist = setOf("test-provider"))
        val provider = TestStreamingDiagnosticProvider(id = "test-provider", enabledByDefault = true)

        assertFalse(config.isProviderEnabled(provider))
    }

    @Test
    fun `allowlist overrides enabledByDefault=false`() {
        val config = DiagnosticConfig(
            denylist = emptySet(),
            allowlist = setOf("disabled-provider"),
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
