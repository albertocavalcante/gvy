package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic

/**
 * Interface for all CodeNarc quick fixers.
 *
 * This interface defines the contract for implementing fixes for specific CodeNarc rules.
 * Each fixer should handle one or more related CodeNarc rules and provide both individual
 * and batch fixing capabilities.
 */
interface CodeNarcQuickFixer {

    /**
     * Metadata about this fixer, including the rule it handles and its characteristics.
     */
    val metadata: FixerMetadata

    /**
     * Determines if this fixer can handle the given diagnostic.
     *
     * @param diagnostic The LSP diagnostic to check
     * @param context The fix context with additional information
     * @return true if this fixer can handle the diagnostic, false otherwise
     */
    fun canFix(diagnostic: Diagnostic, context: FixContext): Boolean

    /**
     * Computes a code action to fix the given diagnostic.
     *
     * @param diagnostic The diagnostic to fix
     * @param context The fix context with document and configuration information
     * @return A CodeAction that can fix the issue, or null if no fix is possible
     */
    fun computeAction(diagnostic: Diagnostic, context: FixContext): CodeAction?

    /**
     * Computes a code action to fix all instances of this rule's violations.
     *
     * @param diagnostics All diagnostics that this fixer can handle
     * @param context The fix context
     * @return A CodeAction that fixes all relevant issues, or null if no fix is possible
     */
    fun computeFixAllAction(diagnostics: List<Diagnostic>, context: FixContext): CodeAction?
}
