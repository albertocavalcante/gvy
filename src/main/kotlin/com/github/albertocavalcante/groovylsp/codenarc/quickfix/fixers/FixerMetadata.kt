package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

/**
 * Categories for organizing different types of fixers.
 */
enum class FixerCategory {
    FORMATTING, // Whitespace, braces, indentation
    IMPORTS, // Import organization and cleanup
    CODE_STYLE, // Naming, unnecessary elements
    REFACTORING, // Structural code changes
    SECURITY, // Security-related fixes
    PERFORMANCE, // Performance improvements
    BEST_PRACTICES, // General best practice fixes
}

/**
 * Metadata about a quick fixer, used for registration and discovery.
 *
 * @property ruleName The CodeNarc rule this fixer handles
 * @property category The category this fixer belongs to
 * @property priority Priority for this fixer (1 = highest, 10 = lowest)
 * @property isPreferred Whether this is the preferred fix for the rule
 * @property dependencies List of other rules this fixer depends on
 * @property triggers List of other rules this fixer should trigger
 */
data class FixerMetadata(
    val ruleName: String,
    val category: FixerCategory,
    val priority: Int = DEFAULT_PRIORITY,
    val isPreferred: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
) {
    companion object {
        // Priority constants for fixer validation
        private const val MIN_PRIORITY = 1
        private const val MAX_PRIORITY = 10
        private const val DEFAULT_PRIORITY = 5
    }

    init {
        require(ruleName.isNotBlank()) { "Rule name cannot be blank" }
        require(priority in MIN_PRIORITY..MAX_PRIORITY) { "Priority must be between $MIN_PRIORITY and $MAX_PRIORITY" }
    }
}
