package com.github.albertocavalcante.groovylsp.providers.semantictokens

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests for semantic token legend configuration.
 *
 * Ensures the legend includes all necessary modifiers for features like
 * unused import dimming.
 */
class SemanticTokenLegendTest {

    @Test
    fun `legend should include unnecessary modifier for unused code dimming`() {
        val modifiers = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS

        assertTrue(
            modifiers.contains("unnecessary"),
            "Legend modifiers should include 'unnecessary' for unused import/code dimming. " +
                "Current modifiers: $modifiers",
        )
    }

    @Test
    fun `unnecessary modifier index should be valid for bitmask computation`() {
        val modifiers = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS
        val index = modifiers.indexOf("unnecessary")

        assertTrue(
            index >= 0,
            "unnecessary modifier should be present in legend",
        )

        // Verify index is reasonable (LSP supports up to 32 modifiers via bitmask)
        assertTrue(
            index < 32,
            "unnecessary modifier index ($index) should be < 32 for valid bitmask",
        )
    }
}
