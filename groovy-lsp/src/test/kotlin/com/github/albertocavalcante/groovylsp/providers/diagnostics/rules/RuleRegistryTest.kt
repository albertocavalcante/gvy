package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleRegistryTest {

    private lateinit var registry: RuleRegistry

    @BeforeEach
    fun setup() {
        registry = RuleRegistry()
    }

    @Test
    fun `should register a rule`() {
        val rule = createTestRule("test-rule")
        registry.register(rule)

        assertTrue(registry.hasRule("test-rule"))
        assertEquals(1, registry.size())
    }

    @Test
    fun `should register multiple rules`() {
        val rule1 = createTestRule("rule-1")
        val rule2 = createTestRule("rule-2")

        registry.registerAll(rule1, rule2)

        assertEquals(2, registry.size())
        assertTrue(registry.hasRule("rule-1"))
        assertTrue(registry.hasRule("rule-2"))
    }

    @Test
    fun `should replace duplicate rule IDs`() {
        val rule1 = createTestRule("test-rule", "First description")
        val rule2 = createTestRule("test-rule", "Second description")

        registry.register(rule1)
        registry.register(rule2)

        assertEquals(1, registry.size())
        val retrieved = registry.getRule("test-rule")
        assertNotNull(retrieved)
        assertEquals("Second description", retrieved.description)
    }

    @Test
    fun `should get all registered rules`() {
        registry.registerAll(
            createTestRule("rule-1"),
            createTestRule("rule-2"),
            createTestRule("rule-3"),
        )

        val allRules = registry.getAllRules()
        assertEquals(3, allRules.size)
    }

    @Test
    fun `should filter enabled rules`() {
        registry.registerAll(
            createTestRule("enabled-1", enabledByDefault = true),
            createTestRule("disabled-1", enabledByDefault = false),
            createTestRule("enabled-2", enabledByDefault = true),
        )

        val enabledRules = registry.getEnabledRules()
        assertEquals(2, enabledRules.size)
        assertTrue(enabledRules.all { it.enabledByDefault })
    }

    @Test
    fun `should get rule by ID`() {
        val rule = createTestRule("test-rule")
        registry.register(rule)

        val retrieved = registry.getRule("test-rule")
        assertNotNull(retrieved)
        assertEquals("test-rule", retrieved.id)
    }

    @Test
    fun `should return null for non-existent rule`() {
        val retrieved = registry.getRule("non-existent")
        assertNull(retrieved)
    }

    @Test
    fun `should get multiple rules by IDs`() {
        registry.registerAll(
            createTestRule("rule-1"),
            createTestRule("rule-2"),
            createTestRule("rule-3"),
        )

        val rules = registry.getRules(listOf("rule-1", "rule-3", "non-existent"))
        assertEquals(2, rules.size)
        assertEquals(setOf("rule-1", "rule-3"), rules.map { it.id }.toSet())
    }

    @Test
    fun `should clear all rules`() {
        registry.registerAll(
            createTestRule("rule-1"),
            createTestRule("rule-2"),
        )

        registry.clear()

        assertEquals(0, registry.size())
        assertFalse(registry.hasRule("rule-1"))
    }

    @Test
    fun `should build registry with DSL`() {
        val registry = buildRuleRegistry {
            rule(createTestRule("rule-1"))
            rule(createTestRule("rule-2"))
            rules(
                createTestRule("rule-3"),
                createTestRule("rule-4"),
            )
        }

        assertEquals(4, registry.size())
        assertTrue(registry.hasRule("rule-1"))
        assertTrue(registry.hasRule("rule-4"))
    }

    private fun createTestRule(
        id: String,
        desc: String = "Test rule",
        enabledByDefault: Boolean = true,
    ): DiagnosticRule = object : AbstractDiagnosticRule() {
        override val id = id
        override val description = desc
        override val defaultSeverity = DiagnosticSeverity.Warning
        override val enabledByDefault = enabledByDefault

        override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> =
            emptyList()
    }
}
