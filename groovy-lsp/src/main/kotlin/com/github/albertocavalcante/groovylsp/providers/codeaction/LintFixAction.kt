package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory

/**
 * Provides lint fix actions for deterministic CodeNarc issues.
 * Only offers safe, simple transformations; complex or risky fixes are not provided.
 *
 * Uses a registry-based architecture where each CodeNarc rule name maps to a fix handler function.
 * This allows for easy extensibility and clear separation of concerns.
 */
class LintFixAction {
    private val logger = LoggerFactory.getLogger(LintFixAction::class.java)

    /**
     * Creates lint fix actions for deterministic CodeNarc diagnostics.
     * Only returns actions for issues with clear, safe fixes.
     *
     * @param uriString The URI of the document
     * @param diagnostics The list of diagnostics to process
     * @param content The full content of the document
     * @return List of CodeActions for diagnostics that have registered handlers
     */
    fun createLintFixActions(uriString: String, diagnostics: List<Diagnostic>, content: String): List<CodeAction> {
        val lines = content.lines()

        return diagnostics.mapNotNull { diagnostic ->
            createFixForDiagnostic(uriString, diagnostic, content, lines)
        }
    }

    /**
     * Creates a fix action for a specific diagnostic if possible.
     * Returns null if no deterministic fix is available.
     *
     * @param uriString The URI of the document
     * @param diagnostic The diagnostic to create a fix for
     * @param content The full content of the document
     * @param lines The content split into lines
     * @return A CodeAction if a fix is available, null otherwise
     */
    @Suppress("ReturnCount") // Multiple early returns for clarity in validation chain
    private fun createFixForDiagnostic(
        uriString: String,
        diagnostic: Diagnostic,
        content: String,
        lines: List<String>,
    ): CodeAction? {
        // Validate source is CodeNarc
        if (!isCodeNarcDiagnostic(diagnostic)) return null

        // Get rule name and handler
        val ruleName = extractRuleName(diagnostic) ?: run {
            logger.debug("No rule name found in diagnostic code")
            return null
        }
        val handler = FixHandlerRegistry.getHandler(ruleName) ?: run {
            logger.debug("No handler registered for rule: $ruleName")
            return null
        }

        // Create context and invoke handler
        val context = FixContext(diagnostic, content, lines, uriString)
        val textEdit = handler(context) ?: run {
            logger.debug("Handler returned null for rule: $ruleName")
            return null
        }

        // Build and return CodeAction
        val title = FixHandlerRegistry.getTitle(ruleName)
        logger.debug("Created lint fix action for rule: $ruleName")
        return createCodeAction(uriString, title, textEdit, diagnostic)
    }

    /**
     * Checks if the diagnostic is from CodeNarc.
     */
    private fun isCodeNarcDiagnostic(diagnostic: Diagnostic): Boolean {
        val source = diagnostic.source ?: return false
        if (!source.equals("CodeNarc", ignoreCase = true)) {
            logger.debug("Skipping non-CodeNarc diagnostic: source=$source")
            return false
        }
        return true
    }

    /**
     * Extracts the rule name from a diagnostic's code field.
     *
     * @param diagnostic The diagnostic to extract the rule name from
     * @return The rule name as a string, or null if not available
     */
    private fun extractRuleName(diagnostic: Diagnostic): String? {
        val code = diagnostic.code ?: return null
        return when {
            code.isLeft -> code.left
            code.isRight -> code.right?.toString()
            else -> null
        }
    }

    /**
     * Creates a CodeAction with the given parameters.
     *
     * @param uriString The URI of the document
     * @param title The title of the code action
     * @param textEdit The text edit to apply
     * @param diagnostic The diagnostic this action fixes
     * @return A CodeAction with kind QuickFix
     */
    private fun createCodeAction(
        uriString: String,
        title: String,
        textEdit: TextEdit,
        diagnostic: Diagnostic,
    ): CodeAction {
        val workspaceEdit = WorkspaceEdit().apply {
            changes = mapOf(uriString to listOf(textEdit))
        }

        return CodeAction(title).apply {
            kind = CodeActionKind.QuickFix
            edit = workspaceEdit
            diagnostics = listOf(diagnostic)
        }
    }
}
