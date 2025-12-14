package com.github.albertocavalcante.groovylsp.providers.codeaction

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for FixHandlerRegistry.
 * **Feature: codenarc-lint-fixes, Property 1: Registry Lookup Consistency**
 * **Validates: Requirements 1.1, 1.2**
 */
class FixHandlerRegistryTest {

    @Test
    fun `registered handler returns non-null for TrailingWhitespace`() {
        val handler = FixHandlerRegistry.getHandler("TrailingWhitespace")
        assertNotNull(handler, "TrailingWhitespace should have a registered handler")
    }

    @Test
    fun `unregistered handler returns null`() {
        val handler = FixHandlerRegistry.getHandler("NonExistentRule")
        assertNull(handler, "Non-existent rule should return null handler")
    }

    @Test
    fun `getTitle returns correct title for registered rule`() {
        val title = FixHandlerRegistry.getTitle("TrailingWhitespace")
        assertEquals("Remove trailing whitespace", title)
    }

    @Test
    fun `getTitle returns default title for unregistered rule`() {
        val title = FixHandlerRegistry.getTitle("NonExistentRule")
        assertEquals("Fix NonExistentRule", title)
    }

    /**
     * Property test: Registry Lookup Consistency
     * **Feature: codenarc-lint-fixes, Property 1: Registry Lookup Consistency**
     * **Validates: Requirements 1.1, 1.2**
     *
     * For any registered rule name, looking up the handler should return a non-null handler,
     * and for any unregistered rule name, the lookup should return null.
     */
    @Property(tries = 100)
    fun `property - unregistered random rule names return null handler`(
        @ForAll @AlphaChars @StringLength(min = 1, max = 50) randomRuleName: String,
    ): Boolean {
        val registeredRules = FixHandlerRegistry.getRegisteredRules()

        return if (registeredRules.contains(randomRuleName)) {
            // For registered rules, handler should be non-null
            FixHandlerRegistry.getHandler(randomRuleName) != null
        } else {
            // For unregistered rules, handler should be null
            FixHandlerRegistry.getHandler(randomRuleName) == null
        }
    }

    @Test
    fun `all registered rules have handlers`() {
        val registeredRules = FixHandlerRegistry.getRegisteredRules()

        for (ruleName in registeredRules) {
            val handler = FixHandlerRegistry.getHandler(ruleName)
            assertNotNull(handler, "Rule '$ruleName' should have a registered handler")
        }
    }

    @Test
    fun `all registered rules have titles`() {
        val rulesWithTitles = FixHandlerRegistry.getRegisteredRulesWithTitles()
        val registeredRules = FixHandlerRegistry.getRegisteredRules()

        // Validate: all registered rules have titles
        assertEquals(
            registeredRules,
            rulesWithTitles.keys,
            "All registered rules should have titles",
        )

        // Validate: no title is empty or default
        for ((ruleName, title) in rulesWithTitles) {
            assertNotEquals(
                "Fix $ruleName",
                title,
                "Rule '$ruleName' should have a specific title, not default",
            )
            assertTrue(
                title.isNotBlank(),
                "Title for rule '$ruleName' should not be blank",
            )
        }
    }

    @Test
    fun `getRegisteredRules returns non-empty set`() {
        val rules = FixHandlerRegistry.getRegisteredRules()
        assertTrue(rules.isNotEmpty(), "Registry should have at least one rule registered")
    }

    @Test
    fun `getRegisteredRulesWithTitles returns consistent data`() {
        val rulesWithTitles = FixHandlerRegistry.getRegisteredRulesWithTitles()
        val registeredRules = FixHandlerRegistry.getRegisteredRules()

        assertEquals(
            registeredRules.size,
            rulesWithTitles.size,
            "Number of rules should match number of titles",
        )

        for (ruleName in registeredRules) {
            assertTrue(
                rulesWithTitles.containsKey(ruleName),
                "Rule '$ruleName' should have a title in rulesWithTitles map",
            )
        }
    }

    @Test
    fun `handler and title lookups are consistent`() {
        val registeredRules = FixHandlerRegistry.getRegisteredRules()

        for (ruleName in registeredRules) {
            // If a rule has a handler, it should have a title
            val handler = FixHandlerRegistry.getHandler(ruleName)
            val title = FixHandlerRegistry.getTitle(ruleName)

            assertNotNull(handler, "Rule '$ruleName' should have a handler")
            assertNotEquals(
                "Fix $ruleName",
                title,
                "Rule '$ruleName' should have a specific title, not default",
            )
        }
    }
}
