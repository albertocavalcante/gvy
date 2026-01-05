package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.providers.diagnostics.StreamingDiagnosticProvider

/**
 * Configuration for diagnostic providers.
 * Follows kotlin-lsp pattern with explicit enabled/disabled provider sets.
 *
 * Priority order:
 * 1. Disabled providers (highest priority - always disables)
 * 2. Enabled providers (overrides enabledByDefault)
 * 3. Provider's enabledByDefault setting
 */
data class DiagnosticConfig(
    /**
     * Provider IDs to disable (never run, even if enabled by default).
     * Example: setOf("unused-imports") for performance-sensitive environments.
     *
     * NOTE: Disabled providers take precedence over enabled providers and enabledByDefault.
     */
    val disabledProviders: Set<String> = emptySet(),

    /**
     * Provider IDs to explicitly enable (even if disabled by default).
     * Example: setOf("unused-imports") to opt-in to slow providers.
     */
    val enabledProviders: Set<String> = emptySet(),
) {
    /**
     * Determine if a provider should be enabled based on configuration.
     *
     * Priority:
     * 1. If in disabled providers -> disabled
     * 2. If in enabled providers -> enabled
     * 3. Use provider's enabledByDefault setting
     */
    fun isProviderEnabled(provider: StreamingDiagnosticProvider): Boolean =
        isProviderEnabled(provider.id, provider.enabledByDefault)

    fun isProviderEnabled(id: String, enabledByDefault: Boolean = true): Boolean {
        if (id in disabledProviders) return false
        if (id in enabledProviders) return true
        return enabledByDefault
    }
}
