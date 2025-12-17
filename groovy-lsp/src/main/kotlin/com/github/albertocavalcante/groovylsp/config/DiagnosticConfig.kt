package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.providers.diagnostics.StreamingDiagnosticProvider

/**
 * Configuration for diagnostic providers.
 * Follows kotlin-lsp pattern with denylist/allowlist support.
 *
 * Priority order:
 * 1. Denylist (highest priority - always disables)
 * 2. Allowlist (overrides enabledByDefault)
 * 3. Provider's enabledByDefault setting
 */
data class DiagnosticConfig(
    /**
     * Provider IDs to deny (never run, even if enabled by default).
     * Example: setOf("unused-imports") for performance-sensitive environments.
     *
     * NOTE: Denylist takes precedence over allowlist and enabledByDefault
     */
    val denylist: Set<String> = emptySet(),

    /**
     * Provider IDs to explicitly enable (even if disabled by default).
     * Example: setOf("unused-imports") to opt-in to slow providers.
     */
    val allowlist: Set<String> = emptySet(),
) {
    /**
     * Determine if a provider should be enabled based on configuration.
     *
     * Priority:
     * 1. If in denylist -> disabled
     * 2. If in allowlist -> enabled
     * 3. Use provider's enabledByDefault setting
     */
    fun isProviderEnabled(provider: StreamingDiagnosticProvider): Boolean {
        if (provider.id in denylist) return false
        if (provider.id in allowlist) return true
        return provider.enabledByDefault
    }
}
