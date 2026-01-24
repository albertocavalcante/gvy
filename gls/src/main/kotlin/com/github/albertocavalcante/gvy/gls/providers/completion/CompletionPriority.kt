package com.github.albertocavalcante.gvy.gls.providers.completion

/**
 * Priority levels for completion items. Lower numbers = higher priority (sort first).
 *
 * LSP clients sort by sortText lexicographically, so we use zero-padded prefixes
 * to ensure proper ordering (e.g., "05-myVar" sorts before "12-abstract").
 *
 * Priority order rationale:
 * - Local context (variables, parameters, fields) should appear first
 * - Methods in current class before external methods
 * - Workspace content before classpath content
 * - Jenkins-specific items for pipeline context
 * - Keywords last (least specific)
 */
object CompletionPriority {
    /** Local variables in current scope - highest priority */
    const val LOCAL_VARIABLE = 0

    /** Method/closure parameters */
    const val PARAMETER = 1

    /** Class fields (properties) */
    const val FIELD = 2

    /** Local methods in current class */
    const val METHOD = 3

    /** Map literal keys, properties - context-specific */
    const val MAP_KEY = 4

    /** Groovy Development Kit (GDK) methods for type */
    const val GDK_METHOD = 5

    /** Classpath methods from Java classes */
    const val CLASSPATH_METHOD = 6

    /** Imported classes */
    const val IMPORTED_CLASS = 7

    /** Classes from workspace (cross-file) */
    const val WORKSPACE_CLASS = 8

    /** Classes from classpath */
    const val CLASSPATH_CLASS = 9

    /** Jenkins pipeline steps */
    const val JENKINS_STEP = 10

    /** Jenkins global variables */
    const val JENKINS_GLOBAL = 11

    /** Groovy keywords (def, abstract, etc.) */
    const val KEYWORD = 12

    /**
     * Format a sortText string for LSP completion item.
     *
     * @param priority The priority level (lower = higher priority)
     * @param label The completion item label for secondary sorting
     * @return A string like "05-myVariable" that sorts lexicographically
     */
    fun sortText(priority: Int, label: String): String = "${priority.toString().padStart(2, '0')}-$label"
}
