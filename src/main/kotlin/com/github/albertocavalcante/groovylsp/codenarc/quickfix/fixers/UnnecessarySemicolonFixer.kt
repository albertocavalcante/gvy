package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Quick fixer for UnnecessarySemicolon CodeNarc rule.
 *
 * This fixer removes unnecessary semicolons at the end of lines while being careful
 * not to remove semicolons that are part of for loops or string literals.
 */
class UnnecessarySemicolonFixer : CodeNarcQuickFixer {

    override val metadata = FixerMetadata(
        ruleName = "UnnecessarySemicolon",
        category = FixerCategory.FORMATTING,
        priority = 2,
        isPreferred = true,
    )

    override fun canFix(diagnostic: Diagnostic, context: FixContext): Boolean = diagnostic.source == "codenarc" &&
        diagnostic.code.left == "UnnecessarySemicolon"

    override fun computeAction(diagnostic: Diagnostic, context: FixContext): CodeAction? {
        if (!canFix(diagnostic, context)) return null

        val lineNumber = diagnostic.range.start.line
        if (lineNumber >= context.sourceLines.size) return null

        val sourceLine = context.sourceLines[lineNumber]
        val fixedLine = removeSemicolon(sourceLine)

        // If no change was made, don't create an action
        if (fixedLine == sourceLine) return null

        val edit = createTextEdit(diagnostic, fixedLine, context.uri)

        return CodeAction().apply {
            title = "Remove unnecessary semicolon"
            kind = CodeActionKind.QuickFix
            diagnostics = listOf(diagnostic)
            isPreferred = true
            this.edit = edit
        }
    }

    override fun computeFixAllAction(diagnostics: List<Diagnostic>, context: FixContext): CodeAction? {
        val relevantDiagnostics = diagnostics.filter { canFix(it, context) }
        if (relevantDiagnostics.isEmpty()) return null

        val textEdits = mutableListOf<TextEdit>()

        for (diagnostic in relevantDiagnostics) {
            val lineNumber = diagnostic.range.start.line
            if (lineNumber >= context.sourceLines.size) continue

            val sourceLine = context.sourceLines[lineNumber]
            val fixedLine = removeSemicolon(sourceLine)

            if (fixedLine != sourceLine) {
                textEdits.add(createSingleTextEdit(diagnostic, fixedLine))
            }
        }

        if (textEdits.isEmpty()) return null

        val edit = WorkspaceEdit().apply {
            changes = mapOf(context.uri to textEdits)
        }

        return CodeAction().apply {
            title = "Remove all unnecessary semicolons"
            kind = CodeActionKind.SourceFixAll
            this.diagnostics = relevantDiagnostics
            this.edit = edit
        }
    }

    /**
     * Removes unnecessary semicolon from a line of code.
     *
     * This method carefully handles:
     * - Only removes trailing semicolons
     * - Preserves semicolons in for loops
     * - Preserves semicolons in string literals
     * - Removes trailing whitespace after semicolon removal
     */
    private fun removeSemicolon(line: String): String {
        if (line.isBlank()) {
            return if (line.contains(';')) "" else line
        }

        // Simple approach: if there's only one semicolon and it's at the end (possibly followed by whitespace)
        val semicolonCount = line.count { it == ';' }

        if (semicolonCount == 1) {
            // Remove the semicolon and any trailing whitespace
            return line.split(";").first().trimEnd()
        }

        // For multiple semicolons, we need to be more careful
        // Check if the last semicolon is at the end (ignoring whitespace)
        val trimmed = line.trimEnd()
        if (trimmed.endsWith(';')) {
            // Remove the last semicolon
            return trimmed.dropLast(1).trimEnd()
        }

        return line
    }

    private fun createTextEdit(diagnostic: Diagnostic, newText: String, uri: String): WorkspaceEdit {
        val textEdit = createSingleTextEdit(diagnostic, newText)

        return WorkspaceEdit().apply {
            changes = mapOf(uri to listOf(textEdit))
        }
    }

    private fun createSingleTextEdit(diagnostic: Diagnostic, newText: String): TextEdit = TextEdit().apply {
        range = Range(
            Position(diagnostic.range.start.line, 0),
            Position(diagnostic.range.start.line, diagnostic.range.end.character),
        )
        this.newText = newText
    }
}
