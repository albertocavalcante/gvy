package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * A function that creates a TextEdit for a specific CodeNarc violation.
 *
 * Returns null if the fix cannot be applied.
 *
 * Handlers should return null in scenarios such as:
 * - The input context is malformed or missing required information
 *   (e.g., the violation location is out of bounds)
 * - The code pattern is too complex or ambiguous to fix automatically
 *   (e.g., nested string interpolations with special characters)
 * - The fix would require semantic understanding not available in the context
 *   (e.g., determining correct import for ambiguous class name)
 * - The violation is detected, but the code is already in a valid state
 *   (e.g., whitespace already removed by another process)
 * - The handler encounters an edge case it doesn't support
 *   (e.g., unusual Groovy syntax variations)
 *
 * Example: A handler for removing trailing whitespace should return null if:
 * - The line number in the diagnostic is beyond the file's line count
 * - The line contains only whitespace (removing might change semantics)
 * - The whitespace is inside a multi-line string literal
 */
typealias FixHandler = (FixContext) -> TextEdit?

/**
 * Registration data for a single fix handler.
 *
 * @property handler The function that applies the fix
 * @property title The human-readable title for the code action
 */
private data class FixRegistration(val handler: FixHandler, val title: String)

/**
 * Registry of fix handlers keyed by CodeNarc rule name.
 * Provides O(1) lookup for fix handlers and their associated titles.
 *
 * Uses a single map design to ensure handler-title pairs are always synchronized.
 */
object FixHandlerRegistry {
    private val registeredFixes: Map<String, FixRegistration> = mapOf(
        // Phase 1: Whitespace/Formatting
        "TrailingWhitespace" to FixRegistration(
            handler = ::fixTrailingWhitespace,
            title = "Remove trailing whitespace",
        ),
        "UnnecessarySemicolon" to FixRegistration(
            handler = ::fixUnnecessarySemicolon,
            title = "Remove unnecessary semicolon",
        ),
        "ConsecutiveBlankLines" to FixRegistration(
            handler = ::fixConsecutiveBlankLines,
            title = "Remove consecutive blank lines",
        ),
        "BlankLineBeforePackage" to FixRegistration(
            handler = ::fixBlankLineBeforePackage,
            title = "Remove blank lines before package",
        ),

        // Phase 2: Import Cleanup
        "UnusedImport" to FixRegistration(
            handler = ::fixRemoveImportLine,
            title = "Remove unused import",
        ),
        "DuplicateImport" to FixRegistration(
            handler = ::fixRemoveImportLine,
            title = "Remove duplicate import",
        ),
        "UnnecessaryGroovyImport" to FixRegistration(
            handler = ::fixRemoveImportLine,
            title = "Remove unnecessary import",
        ),
        "ImportFromSamePackage" to FixRegistration(
            handler = ::fixRemoveImportLine,
            title = "Remove same-package import",
        ),

        // Phase 3: Convention Fixes
        "UnnecessaryPublicModifier" to FixRegistration(
            handler = ::fixUnnecessaryPublicModifier,
            title = "Remove unnecessary 'public'",
        ),
        "UnnecessaryDefInVariableDeclaration" to FixRegistration(
            handler = ::fixUnnecessaryDef,
            title = "Remove unnecessary 'def'",
        ),
        "UnnecessaryGetter" to FixRegistration(
            handler = ::fixUnnecessaryGetter,
            title = "Use property access",
        ),
        "UnnecessarySetter" to FixRegistration(
            handler = ::fixUnnecessarySetter,
            title = "Use property assignment",
        ),
        "UnnecessaryDotClass" to FixRegistration(
            handler = ::fixUnnecessaryDotClass,
            title = "Remove '.class'",
        ),
    )

    /**
     * Gets the fix handler for a given CodeNarc rule name.
     * @param ruleName The CodeNarc rule name (e.g., "TrailingWhitespace")
     * @return The fix handler function, or null if no handler is registered
     */
    fun getHandler(ruleName: String): FixHandler? = registeredFixes[ruleName]?.handler

    /**
     * Gets the title for a fix action for a given rule.
     * @param ruleName The CodeNarc rule name
     * @return The human-readable title for the fix action
     */
    fun getTitle(ruleName: String): String = registeredFixes[ruleName]?.title ?: "Fix $ruleName"

    /**
     * Returns all registered rule names.
     * Useful for testing and introspection.
     *
     * @return Set of all registered CodeNarc rule names
     */
    fun getRegisteredRules(): Set<String> = registeredFixes.keys

    /**
     * Returns all registered rules with their titles.
     * Useful for testing and generating documentation.
     *
     * @return Map of rule names to their human-readable titles
     */
    fun getRegisteredRulesWithTitles(): Map<String, String> = registeredFixes.mapValues { it.value.title }
}

// ============================================================================
// Fix Handler Implementations
// ============================================================================

/**
 * Fix handler for TrailingWhitespace rule.
 * Removes trailing whitespace from the affected line.
 *
 * **Feature: codenarc-lint-fixes, Property 3: Trailing Whitespace Removal**
 * **Validates: Requirements 2.1**
 *
 * @param context The fix context containing diagnostic and source information
 * @return A TextEdit that removes trailing whitespace, or null if the fix cannot be applied
 */
private fun fixTrailingWhitespace(context: FixContext): TextEdit? {
    val lineNumber = context.diagnostic.range.start.line

    // Validate line number is within bounds
    if (lineNumber < 0 || lineNumber >= context.lines.size) {
        return null
    }

    val line = context.lines[lineNumber]
    val trimmedLine = line.trimEnd()

    // Return null if no trailing whitespace to remove (no-op case)
    if (line == trimmedLine) {
        return null
    }

    // Create TextEdit to remove only the trailing whitespace (minimal edit)
    val range = Range(
        Position(lineNumber, trimmedLine.length),
        Position(lineNumber, line.length),
    )

    return TextEdit(range, "")
}

// Placeholder fix handler implementations - will be implemented in later tasks
// These functions return null until their respective implementation tasks are completed.
// The FunctionOnlyReturningConstant warning is expected for placeholders.

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixUnnecessarySemicolon(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixConsecutiveBlankLines(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixBlankLineBeforePackage(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixRemoveImportLine(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixUnnecessaryPublicModifier(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixUnnecessaryDef(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixUnnecessaryGetter(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixUnnecessarySetter(context: FixContext): TextEdit? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
private fun fixUnnecessaryDotClass(context: FixContext): TextEdit? = null
