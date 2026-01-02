package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import org.slf4j.LoggerFactory

/**
 * Registry for managing diagnostic rules.
 *
 * Provides a central place to register and retrieve rules, with support
 * for filtering by enabled state and other criteria.
 *
 * NOTE: This is a simple implementation. In the future, we could add:
 * - Rule configuration from settings
 * - Dynamic rule loading
 * - Rule dependencies
 */
class RuleRegistry {
    private val rules = mutableListOf<DiagnosticRule>()
    private val logger = LoggerFactory.getLogger(RuleRegistry::class.java)

    /**
     * Register a rule.
     * Duplicate IDs are logged as warnings but allowed (last one wins).
     */
    fun register(rule: DiagnosticRule) {
        val existing = rules.find { it.id == rule.id }
        if (existing != null) {
            logger.warn("Duplicate rule ID: ${rule.id}, replacing existing rule")
            rules.remove(existing)
        }
        rules.add(rule)
        logger.debug("Registered rule: ${rule.id} (${rule.description})")
    }

    /**
     * Register multiple rules.
     */
    fun registerAll(vararg rulesToAdd: DiagnosticRule) {
        rulesToAdd.forEach { register(it) }
    }

    /**
     * Get all registered rules.
     */
    fun getAllRules(): List<DiagnosticRule> = rules.toList()

    /**
     * Get all rules that are enabled by default.
     */
    fun getEnabledRules(): List<DiagnosticRule> = rules.filter { it.enabledByDefault }

    /**
     * Get a rule by ID.
     */
    fun getRule(id: String): DiagnosticRule? = rules.find { it.id == id }

    /**
     * Get rules by IDs.
     */
    fun getRules(ids: Collection<String>): List<DiagnosticRule> =
        ids.mapNotNull { id -> getRule(id) }

    /**
     * Check if a rule is registered.
     */
    fun hasRule(id: String): Boolean = rules.any { it.id == id }

    /**
     * Get count of registered rules.
     */
    fun size(): Int = rules.size

    /**
     * Clear all registered rules.
     * Mainly useful for testing.
     */
    fun clear() {
        rules.clear()
    }
}

/**
 * DSL builder for configuring rules.
 *
 * Example:
 * ```
 * val registry = buildRuleRegistry {
 *     rule(MyCustomRule())
 *     rule(AnotherRule())
 * }
 * ```
 */
class RuleRegistryBuilder {
    private val registry = RuleRegistry()

    /**
     * Add a rule to the registry.
     */
    fun rule(rule: DiagnosticRule) {
        registry.register(rule)
    }

    /**
     * Add multiple rules.
     */
    fun rules(vararg rulesToAdd: DiagnosticRule) {
        registry.registerAll(*rulesToAdd)
    }

    internal fun build(): RuleRegistry = registry
}

/**
 * Build a rule registry using DSL syntax.
 */
fun buildRuleRegistry(block: RuleRegistryBuilder.() -> Unit): RuleRegistry =
    RuleRegistryBuilder().apply(block).build()
