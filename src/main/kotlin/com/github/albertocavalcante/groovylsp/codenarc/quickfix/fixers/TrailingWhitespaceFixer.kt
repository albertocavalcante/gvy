package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Quick fixer for TrailingWhitespace CodeNarc rule.
 *
 * This fixer removes trailing whitespace (spaces and tabs) from lines
 * while preserving leading whitespace and content.
 */
class TrailingWhitespaceFixer : CodeNarcQuickFixer {

    override val metadata = FixerMetadata(
        ruleName = "TrailingWhitespace",
        category = FixerCategory.FORMATTING,
        priority = 1, // High priority for formatting issues
        isPreferred = true,
    )

    override fun canFix(diagnostic: Diagnostic, context: FixContext): Boolean = diagnostic.source == "codenarc" &&
        diagnostic.code.left == "TrailingWhitespace"

    override fun computeAction(diagnostic: Diagnostic, context: FixContext): CodeAction? {
        if (!canFix(diagnostic, context)) return null

        val lineNumber = diagnostic.range.start.line
        if (lineNumber >= context.sourceLines.size) return null

        val sourceLine = context.sourceLines[lineNumber]
        val fixedLine = removeTrailingWhitespace(sourceLine)

        // If no change was made, don't create an action
        if (fixedLine == sourceLine) return null

        val edit = createTextEdit(lineNumber, sourceLine, fixedLine, context.uri)

        return CodeAction().apply {
            title = "Remove trailing whitespace"
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
            val fixedLine = removeTrailingWhitespace(sourceLine)

            if (fixedLine != sourceLine) {
                textEdits.add(createSingleTextEdit(lineNumber, sourceLine, fixedLine))
            }
        }

        if (textEdits.isEmpty()) return null

        val edit = WorkspaceEdit().apply {
            changes = mapOf(context.uri to textEdits)
        }

        return CodeAction().apply {
            title = "Remove all trailing whitespace"
            kind = CodeActionKind.SourceFixAll
            this.diagnostics = relevantDiagnostics
            this.edit = edit
        }
    }

    /**
     * Removes trailing whitespace (spaces and tabs) from a line.
     *
     * @param line The source line
     * @return The line with trailing whitespace removed
     */
    private fun removeTrailingWhitespace(line: String): String = line.trimEnd()

    private fun createTextEdit(lineNumber: Int, sourceLine: String, newText: String, uri: String): WorkspaceEdit {
        val textEdit = createSingleTextEdit(lineNumber, sourceLine, newText)

        return WorkspaceEdit().apply {
            changes = mapOf(uri to listOf(textEdit))
        }
    }

    private fun createSingleTextEdit(lineNumber: Int, sourceLine: String, newText: String): TextEdit =
        TextEdit().apply {
            range = Range(
                Position(lineNumber, 0),
                Position(lineNumber, sourceLine.length),
            )
            this.newText = newText
        }
}
