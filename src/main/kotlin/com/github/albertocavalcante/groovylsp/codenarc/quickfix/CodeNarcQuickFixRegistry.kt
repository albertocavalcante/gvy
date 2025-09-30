package com.github.albertocavalcante.groovylsp.codenarc.quickfix

import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.CodeNarcQuickFixer
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixerMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry for managing CodeNarc quick fixers.
 *
 * This registry maintains a collection of fixers organized by the CodeNarc rules they handle.
 * It provides efficient lookup and supports multiple fixers per rule, automatically sorted by priority.
 */
class CodeNarcQuickFixRegistry {

    private val fixersByRule = ConcurrentHashMap<String, CopyOnWriteArrayList<CodeNarcQuickFixer>>()

    /**
     * Registers a quick fixer for its associated CodeNarc rule.
     *
     * @param fixer The fixer to register
     * @throws IllegalArgumentException if the fixer's metadata is invalid
     */
    fun register(fixer: CodeNarcQuickFixer) {
        // Metadata validation is done in FixerMetadata constructor
        val ruleName = fixer.metadata.ruleName

        val fixersForRule = fixersByRule.computeIfAbsent(ruleName) { CopyOnWriteArrayList() }
        fixersForRule.add(fixer)

        // Re-sort by priority (1 = highest, 10 = lowest)
        fixersForRule.sortBy { it.metadata.priority }
    }

    /**
     * Gets all fixers that can handle the specified CodeNarc rule.
     *
     * @param ruleName The name of the CodeNarc rule
     * @return List of fixers sorted by priority (highest first), or empty list if none found
     */
    fun getFixers(ruleName: String): List<CodeNarcQuickFixer> = fixersByRule[ruleName]?.toList() ?: emptyList()

    /**
     * Gets all CodeNarc rules that have registered fixers.
     *
     * @return Set of rule names that have at least one registered fixer
     */
    fun getSupportedRules(): Set<String> = fixersByRule.keys.toSet()

    /**
     * Gets all fixers that are candidates for fix-all operations.
     *
     * @return List of all registered fixers
     */
    fun getFixAllCandidates(): List<CodeNarcQuickFixer> = fixersByRule.values.flatMap { it.toList() }

    /**
     * Gets metadata for all fixers registered for the specified rule.
     *
     * @param ruleName The name of the CodeNarc rule
     * @return List of fixer metadata sorted by priority
     */
    fun getFixerMetadata(ruleName: String): List<FixerMetadata> = getFixers(ruleName).map { it.metadata }

    /**
     * Gets the total number of registered fixers.
     *
     * @return Total count of fixers
     */
    fun size(): Int = fixersByRule.values.sumOf { it.size }

    /**
     * Clears all registered fixers.
     */
    fun clear() {
        fixersByRule.clear()
    }
}
