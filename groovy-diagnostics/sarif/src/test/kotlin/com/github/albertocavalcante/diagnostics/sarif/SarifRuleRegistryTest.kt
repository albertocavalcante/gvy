package com.github.albertocavalcante.diagnostics.sarif

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SarifRuleRegistryTest {

    @Test
    fun `should return CodeNarc rules`() {
        val rules = SarifRuleRegistry.getCodeNarcRules()

        assertTrue(rules.isNotEmpty())

        // Check a specific well-known rule
        val emptyClassRule = rules.find { it.id == "EmptyClass" }
        assertNotNull(emptyClassRule)
        assertEquals("EmptyClassRule", emptyClassRule.name)
        assertNotNull(emptyClassRule.shortDescription)
        assertTrue(emptyClassRule.helpUri?.contains("codenarc.org") == true)
    }

    @Test
    fun `should return compiler rules`() {
        val rules = SarifRuleRegistry.getCompilerRules()

        assertEquals(2, rules.size)

        val errorRule = rules.find { it.id == "CompilationError" }
        assertNotNull(errorRule)
        assertEquals(SarifLevel.ERROR, errorRule.defaultConfiguration?.level)

        val warningRule = rules.find { it.id == "CompilationWarning" }
        assertNotNull(warningRule)
        assertEquals(SarifLevel.WARNING, warningRule.defaultConfiguration?.level)
    }

    @Test
    fun `should find rule by ID`() {
        // CodeNarc rule
        val unusedVariable = SarifRuleRegistry.getRule("UnusedVariable")
        assertNotNull(unusedVariable)
        assertEquals("UnusedVariable", unusedVariable.id)

        // Compiler rule
        val compError = SarifRuleRegistry.getRule("CompilationError")
        assertNotNull(compError)
        assertEquals("CompilationError", compError.id)

        // Non-existent rule
        val nonExistent = SarifRuleRegistry.getRule("NonExistentRule")
        assertNull(nonExistent)
    }

    @Test
    fun `should have proper rule metadata`() {
        val rule = SarifRuleRegistry.getRule("CyclomaticComplexity")
        assertNotNull(rule)

        // Check all expected fields are populated
        assertNotNull(rule.name)
        assertNotNull(rule.shortDescription)
        assertNotNull(rule.helpUri)
        assertNotNull(rule.defaultConfiguration)
        val properties = rule.properties
        assertNotNull(properties)
        assertNotNull(properties.priority)
        assertNotNull(properties.category)
    }

    @Test
    fun `should have correct priority to level mapping`() {
        // Priority 1 = Error
        val securityRule = SarifRuleRegistry.getRule("InsecureRandom")
        assertNotNull(securityRule)
        assertEquals(SarifLevel.ERROR, securityRule.defaultConfiguration?.level)
        assertEquals(1, securityRule.properties?.priority)

        // Priority 2 = Warning
        val namingRule = SarifRuleRegistry.getRule("ClassName")
        assertNotNull(namingRule)
        assertEquals(SarifLevel.WARNING, namingRule.defaultConfiguration?.level)
        assertEquals(2, namingRule.properties?.priority)

        // Priority 3 = Note
        val formattingRule = SarifRuleRegistry.getRule("SpaceAfterComma")
        assertNotNull(formattingRule)
        assertEquals(SarifLevel.NOTE, formattingRule.defaultConfiguration?.level)
        assertEquals(3, formattingRule.properties?.priority)
    }

    @Test
    fun `getAllRules should include both CodeNarc and compiler rules`() {
        val allRules = SarifRuleRegistry.getAllRules()

        val codeNarcCount = SarifRuleRegistry.getCodeNarcRules().size
        val compilerCount = SarifRuleRegistry.getCompilerRules().size

        assertEquals(codeNarcCount + compilerCount, allRules.size)
    }

    // Edge case tests

    @Test
    fun `getRule should return null for empty string`() {
        assertNull(SarifRuleRegistry.getRule(""))
    }

    @Test
    fun `getRule should return null for whitespace string`() {
        assertNull(SarifRuleRegistry.getRule("   "))
    }

    @Test
    fun `getRule should be case sensitive`() {
        // Exact match should work
        assertNotNull(SarifRuleRegistry.getRule("EmptyClass"))

        // Wrong case should fail
        assertNull(SarifRuleRegistry.getRule("emptyclass"))
        assertNull(SarifRuleRegistry.getRule("EMPTYCLASS"))
    }

    // Category coverage tests

    @Test
    fun `all categories should have at least one rule`() {
        val expectedCategories = setOf(
            "basic", "formatting", "unused", "imports", "naming",
            "groovyism", "size", "exceptions", "security", "unnecessary", "braces",
        )
        val actualCategories = SarifRuleRegistry.getCodeNarcRules()
            .mapNotNull { it.properties?.category }
            .toSet()
        assertTrue(expectedCategories.all { it in actualCategories })
    }

    @Test
    fun `all rules in a category should have matching category property`() {
        val rules = SarifRuleRegistry.getCodeNarcRules()

        rules.forEach { rule ->
            assertNotNull(rule.properties?.category, "Rule ${rule.id} should have a category")
            assertTrue(
                rule.helpUri?.contains(rule.properties?.category ?: "") == true,
                "Rule ${rule.id} helpUri should contain its category",
            )
        }
    }

    // URL validation tests

    @Test
    fun `all CodeNarc rules should have valid helpUri format`() {
        val rules = SarifRuleRegistry.getCodeNarcRules()

        rules.forEach { rule ->
            val helpUri = rule.helpUri
            assertNotNull(helpUri, "Rule ${rule.id} should have a helpUri")
            assertTrue(
                helpUri.startsWith("https://codenarc.org/codenarc-rules-"),
                "Rule ${rule.id} helpUri should start with codenarc.org base URL",
            )
            assertTrue(
                helpUri.contains(".html#${rule.id}"),
                "Rule ${rule.id} helpUri should contain anchor to rule ID",
            )
        }
    }

    @Test
    fun `compiler rules should have groovy-lang helpUri`() {
        val rules = SarifRuleRegistry.getCompilerRules()

        rules.forEach { rule ->
            assertNotNull(rule.helpUri)
            assertTrue(
                rule.helpUri?.contains("groovy-lang.org") == true,
                "Compiler rule ${rule.id} should have groovy-lang.org URI",
            )
        }
    }

    // Invariant/regression tests

    @Test
    fun `should have expected number of CodeNarc rules`() {
        val ruleCount = SarifRuleRegistry.getCodeNarcRules().size
        // We expect at least 60 rules based on the current implementation
        assertTrue(ruleCount >= 60, "Expected at least 60 CodeNarc rules, but found $ruleCount")
    }

    @Test
    fun `no duplicate rule IDs should exist`() {
        val allRules = SarifRuleRegistry.getAllRules()
        val ruleIds = allRules.map { it.id }
        val uniqueIds = ruleIds.toSet()

        assertEquals(uniqueIds.size, ruleIds.size, "Found duplicate rule IDs")
    }

    @Test
    fun `all rules should have non-null required fields`() {
        val allRules = SarifRuleRegistry.getAllRules()

        allRules.forEach { rule ->
            assertNotNull(rule.id, "Rule should have an ID")
            assertTrue(rule.id.isNotBlank(), "Rule ID should not be blank")
            assertNotNull(rule.name, "Rule ${rule.id} should have a name")
            assertTrue(rule.name.isNotBlank(), "Rule ${rule.id} name should not be blank")
            assertNotNull(rule.shortDescription, "Rule ${rule.id} should have a shortDescription")
            assertNotNull(rule.shortDescription?.text, "Rule ${rule.id} shortDescription.text should not be null")
        }
    }

    // Priority boundary tests

    @Test
    fun `priority 1 should map to ERROR level`() {
        val priority1Rules = SarifRuleRegistry.getCodeNarcRules()
            .filter { it.properties?.priority == 1 }

        assertTrue(priority1Rules.isNotEmpty(), "Should have at least one priority 1 rule")
        priority1Rules.forEach { rule ->
            assertEquals(
                SarifLevel.ERROR,
                rule.defaultConfiguration?.level,
                "Rule ${rule.id} with priority 1 should have ERROR level",
            )
        }
    }

    @Test
    fun `priority 2 should map to WARNING level`() {
        val priority2Rules = SarifRuleRegistry.getCodeNarcRules()
            .filter { it.properties?.priority == 2 }

        assertTrue(priority2Rules.isNotEmpty(), "Should have at least one priority 2 rule")
        priority2Rules.forEach { rule ->
            assertEquals(
                SarifLevel.WARNING,
                rule.defaultConfiguration?.level,
                "Rule ${rule.id} with priority 2 should have WARNING level",
            )
        }
    }

    @Test
    fun `priority 3 should map to NOTE level`() {
        val priority3Rules = SarifRuleRegistry.getCodeNarcRules()
            .filter { it.properties?.priority == 3 }

        assertTrue(priority3Rules.isNotEmpty(), "Should have at least one priority 3 rule")
        priority3Rules.forEach { rule ->
            assertEquals(
                SarifLevel.NOTE,
                rule.defaultConfiguration?.level,
                "Rule ${rule.id} with priority 3 should have NOTE level",
            )
        }
    }

    @Test
    fun `no rules should have priority less than 1`() {
        val allRules = SarifRuleRegistry.getCodeNarcRules()

        allRules.forEach { rule ->
            val priority = rule.properties?.priority
            if (priority != null) {
                assertTrue(priority >= 1, "Rule ${rule.id} has invalid priority $priority (should be >= 1)")
            }
        }
    }

    // Adversarial tests

    @Test
    fun `concurrent access to getRule should be thread safe`() {
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(100) {
                    val rule = SarifRuleRegistry.getRule("EmptyClass")
                    assertNotNull(rule)
                    assertEquals("EmptyClass", rule.id)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun `multiple calls to getCodeNarcRules should return same instances`() {
        val rules1 = SarifRuleRegistry.getCodeNarcRules()
        val rules2 = SarifRuleRegistry.getCodeNarcRules()

        assertEquals(rules1.size, rules2.size)

        // Verify same instances (lazy initialization works correctly)
        val rule1 = SarifRuleRegistry.getRule("EmptyClass")
        val rule2 = SarifRuleRegistry.getRule("EmptyClass")
        assertTrue(rule1 === rule2, "getRule should return same instance on multiple calls")
    }
}
