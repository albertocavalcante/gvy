package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinRulesTest {

    @Test
    fun `getAllRules should return all 5 built-in rules`() {
        val allRules = BuiltinRules.getAllRules()
        
        assertEquals(5, allRules.size, "Should have exactly 5 built-in rules")
        
        val ruleIds = allRules.map { it.id }.toSet()
        assertTrue(ruleIds.contains("println-debug"))
        assertTrue(ruleIds.contains("empty-block"))
        assertTrue(ruleIds.contains("jenkins-stage-structure"))
        assertTrue(ruleIds.contains("spock-test-structure"))
        assertTrue(ruleIds.contains("groovy-null-safety"))
    }

    @Test
    fun `getDefaultRules should only return enabled-by-default rules`() {
        val defaultRules = BuiltinRules.getDefaultRules()
        
        // NullSafetyRule is disabled by default
        assertTrue(defaultRules.size < 5, "Should have fewer than 5 rules (some disabled by default)")
        assertTrue(defaultRules.all { it.enabledByDefault }, "All returned rules should be enabled by default")
        
        val defaultIds = defaultRules.map { it.id }.toSet()
        assertTrue(defaultIds.contains("println-debug"))
        assertTrue(defaultIds.contains("empty-block"))
        assertTrue(defaultIds.contains("jenkins-stage-structure"))
        assertTrue(defaultIds.contains("spock-test-structure"))
    }

    @Test
    fun `getRulesForContext JENKINS should return Jenkins-specific rules`() {
        val jenkinsRules = BuiltinRules.getRulesForContext(BuiltinRules.RuleContext.JENKINS)
        
        assertTrue(jenkinsRules.isNotEmpty())
        val jenkinsIds = jenkinsRules.map { it.id }.toSet()
        assertTrue(jenkinsIds.contains("jenkins-stage-structure"))
        assertTrue(jenkinsIds.contains("println-debug"))
        assertTrue(jenkinsIds.contains("empty-block"))
    }

    @Test
    fun `getRulesForContext SPOCK should return Spock-specific rules`() {
        val spockRules = BuiltinRules.getRulesForContext(BuiltinRules.RuleContext.SPOCK)
        
        assertTrue(spockRules.isNotEmpty())
        val spockIds = spockRules.map { it.id }.toSet()
        assertTrue(spockIds.contains("spock-test-structure"))
        assertTrue(spockIds.contains("empty-block"))
    }

    @Test
    fun `getRulesForContext GENERAL should return general Groovy rules`() {
        val generalRules = BuiltinRules.getRulesForContext(BuiltinRules.RuleContext.GENERAL)
        
        assertTrue(generalRules.isNotEmpty())
        val generalIds = generalRules.map { it.id }.toSet()
        assertTrue(generalIds.contains("println-debug"))
        assertTrue(generalIds.contains("empty-block"))
        assertTrue(generalIds.contains("groovy-null-safety"))
    }

    @Test
    fun `all built-in rules should have unique IDs`() {
        val allRules = BuiltinRules.getAllRules()
        val ids = allRules.map { it.id }
        val uniqueIds = ids.toSet()
        
        assertEquals(ids.size, uniqueIds.size, "All rule IDs should be unique")
    }

    @Test
    fun `all built-in rules should have non-empty descriptions`() {
        val allRules = BuiltinRules.getAllRules()
        
        allRules.forEach { rule ->
            assertTrue(rule.description.isNotBlank(), "Rule ${rule.id} should have a description")
        }
    }

    @Test
    fun `all built-in rules should have valid severity`() {
        val allRules = BuiltinRules.getAllRules()
        
        allRules.forEach { rule ->
            // Just verify it doesn't throw - severity is an enum
            val severity = rule.defaultSeverity
            assertTrue(severity != null, "Rule ${rule.id} should have a severity")
        }
    }
}
