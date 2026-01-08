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
}
