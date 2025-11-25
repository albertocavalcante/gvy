package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory

/**
 * Provides lint fix actions for deterministic CodeNarc issues.
 * Only offers safe, simple transformations; complex or risky fixes are declined.
 */
class LintFixAction {
    private val logger = LoggerFactory.getLogger(LintFixAction::class.java)

    /**
     * Creates lint fix actions for deterministic CodeNarc diagnostics.
     * Only returns actions for issues with clear, safe fixes.
     */
    fun createLintFixActions(uriString: String, diagnostics: List<Diagnostic>, content: String): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()

        // Filter for CodeNarc diagnostics with deterministic fixes
        for (diagnostic in diagnostics) {
            val fixAction = createFixForDiagnostic(uriString, diagnostic, content)
            if (fixAction != null) {
                actions.add(fixAction)
                logger.debug("Created lint fix action for: ${diagnostic.message}")
            }
        }

        return actions
    }

    /**
     * Creates a fix action for a specific diagnostic if possible.
     * Returns null if no deterministic fix is available.
     */
    @Suppress("UnusedParameter") // Parameters kept for future enhancement
    private fun createFixForDiagnostic(
        uriString: String,
        diagnostic: Diagnostic,
        content: String,
    ): CodeAction? {
        val source = diagnostic.source ?: return null

        // Only handle CodeNarc diagnostics
        if (!source.equals("CodeNarc", ignoreCase = true)) {
            return null
        }

        // For now, we start with no specific lint fixes implemented
        // This is a placeholder for future deterministic transformations
        // Examples of safe fixes that could be added:
        // - Remove unused imports
        // - Add missing semicolons (if required by style)
        // - Fix simple spacing issues
        // - Add @Override annotations where missing

        logger.debug("No deterministic fix available for CodeNarc issue: ${diagnostic.message}")
        return null
    }

    /**
     * Creates a code action for a text replacement.
     */
    @Suppress("UnusedPrivateMember") // Kept for future lint fix implementations
    private fun createReplaceAction(
        uriString: String,
        title: String,
        range: Range,
        newText: String,
        diagnostic: Diagnostic,
    ): CodeAction {
        val edit = TextEdit(range, newText)

        val workspaceEdit = WorkspaceEdit().apply {
            changes = mapOf(uriString to listOf(edit))
        }

        return CodeAction(title).apply {
            kind = CodeActionKind.QuickFix
            this.edit = workspaceEdit
            diagnostics = listOf(diagnostic)
        }
    }

    /**
     * Creates a code action to remove text in a range.
     */
    @Suppress("UnusedPrivateMember") // Kept for future lint fix implementations
    private fun createRemoveAction(
        uriString: String,
        title: String,
        range: Range,
        diagnostic: Diagnostic,
    ): CodeAction = createReplaceAction(uriString, title, range, "", diagnostic)
}
