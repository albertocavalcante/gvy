package com.github.albertocavalcante.groovylsp.fixtures

import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import java.net.URI

/**
 * Data classes for grouping parameters
 */
data class DiagnosticSpec(
    val message: String,
    val startLine: Int,
    val startChar: Int,
    val endLine: Int = startLine,
    val endChar: Int = startChar + 1,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
)

data class RangeFormattingSpec(
    val uri: String = "file:///test.groovy",
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
)

/**
 * Test helpers for creating LSP protocol objects
 */
object TestHelpers {

    fun createDiagnostic(spec: DiagnosticSpec): Diagnostic = Diagnostic().apply {
        this.message = spec.message
        this.range = Range(Position(spec.startLine, spec.startChar), Position(spec.endLine, spec.endChar))
        this.severity = spec.severity
    }

    data class DiagnosticParams(
        val message: String,
        val startLine: Int,
        val startChar: Int,
        val endLine: Int = startLine,
        val endChar: Int = startChar + 1,
        val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    )

    fun createDiagnostic(params: DiagnosticParams): Diagnostic = createDiagnostic(
        DiagnosticSpec(
            params.message,
            params.startLine,
            params.startChar,
            params.endLine,
            params.endChar,
            params.severity,
        ),
    )

    fun createCodeActionParams(
        uri: String = "file:///test.groovy",
        range: Range? = null,
        diagnostics: List<Diagnostic> = emptyList(),
        only: List<String>? = null,
    ): CodeActionParams = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(uri)
        this.range = range ?: Range(Position(0, 0), Position(0, 0))
        context = CodeActionContext().apply {
            this.diagnostics = diagnostics
            this.only = only
        }
    }

    fun createPosition(line: Int, character: Int): Position = Position(line, character)

    fun createRange(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range =
        Range(Position(startLine, startChar), Position(endLine, endChar))

    fun createTextDocumentIdentifier(uri: String = "file:///test.groovy"): TextDocumentIdentifier =
        TextDocumentIdentifier(uri)

    fun createFormattingParams(
        uri: String = "file:///test.groovy",
        tabSize: Int = 4,
        insertSpaces: Boolean = true,
    ): DocumentFormattingParams = DocumentFormattingParams().apply {
        textDocument = TextDocumentIdentifier(uri)
        options = FormattingOptions().apply {
            this.tabSize = tabSize
            this.isInsertSpaces = insertSpaces
        }
    }

    fun createRangeFormattingParams(spec: RangeFormattingSpec): DocumentRangeFormattingParams =
        DocumentRangeFormattingParams().apply {
            textDocument = TextDocumentIdentifier(spec.uri)
            range = Range(Position(spec.startLine, spec.startChar), Position(spec.endLine, spec.endChar))
            options = FormattingOptions().apply {
                this.tabSize = spec.tabSize
                this.isInsertSpaces = spec.insertSpaces
            }
        }

    data class RangeFormattingParams(
        val uri: String = "file:///test.groovy",
        val startLine: Int,
        val startChar: Int,
        val endLine: Int,
        val endChar: Int,
        val tabSize: Int = 4,
        val insertSpaces: Boolean = true,
    )

    fun createRangeFormattingParams(params: RangeFormattingParams): DocumentRangeFormattingParams =
        createRangeFormattingParams(
            RangeFormattingSpec(
                params.uri,
                params.startLine,
                params.startChar,
                params.endLine,
                params.endChar,
                params.tabSize,
                params.insertSpaces,
            ),
        )

    fun createDocumentHighlightParams(
        uri: String = "file:///test.groovy",
        line: Int,
        character: Int,
    ): DocumentHighlightParams = DocumentHighlightParams().apply {
        textDocument = TextDocumentIdentifier(uri)
        position = Position(line, character)
    }

    /**
     * Helper to apply text edits to a string for testing
     */
    fun applyTextEdits(originalText: String, edits: List<TextEdit>): String {
        val lines = originalText.lines().toMutableList()

        // Sort edits in reverse order to avoid position shifts
        val sortedEdits = edits.sortedWith(
            compareByDescending<TextEdit> { it.range.start.line }
                .thenByDescending { it.range.start.character },
        )

        for (edit in sortedEdits) {
            val startLine = edit.range.start.line
            val startChar = edit.range.start.character
            val endLine = edit.range.end.line
            val endChar = edit.range.end.character

            when {
                startLine == endLine -> {
                    // Single line edit
                    val line = lines[startLine]
                    lines[startLine] = line.substring(0, startChar) + edit.newText + line.substring(endChar)
                }
                else -> {
                    // Multi-line edit
                    val firstLine = lines[startLine].substring(0, startChar) + edit.newText
                    val lastLine = lines[endLine].substring(endChar)

                    // Remove lines in between
                    for (i in endLine downTo startLine + 1) {
                        lines.removeAt(i)
                    }

                    lines[startLine] = firstLine + lastLine
                }
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Creates a test URI for the given filename
     */
    fun testUri(filename: String = "test.groovy"): URI = URI.create("file:///$filename")
}
