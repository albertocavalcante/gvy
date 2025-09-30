package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TrailingWhitespaceFixerTest {

    private lateinit var fixer: TrailingWhitespaceFixer

    @BeforeEach
    fun setUp() {
        fixer = TrailingWhitespaceFixer()
    }

    @Test
    fun `should have correct metadata`() {
        val metadata = fixer.metadata

        assertEquals("TrailingWhitespace", metadata.ruleName)
        assertEquals(FixerCategory.FORMATTING, metadata.category)
        assertEquals(1, metadata.priority) // High priority for formatting
        assertTrue(metadata.isPreferred)
    }

    @Test
    fun `should handle TrailingWhitespace diagnostic`() {
        val diagnostic = createDiagnostic("TrailingWhitespace")
        val context = createContext(listOf("def x = 1  "))

        assertTrue(fixer.canFix(diagnostic, context))
    }

    @Test
    fun `should not handle other diagnostics`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon")
        val context = createContext(listOf("def x = 1  "))

        assertFalse(fixer.canFix(diagnostic, context))
    }

    @Test
    fun `should compute action to remove trailing spaces`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 0)
        val context = createContext(listOf("def x = 1   "))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        assertEquals("Remove trailing whitespace", action!!.title)
        assertEquals(CodeActionKind.QuickFix, action.kind)
        assertEquals(listOf(diagnostic), action.diagnostics)
        assertTrue(action.isPreferred == true)

        // Check the edit
        assertNotNull(action.edit)
        val changes = action.edit!!.changes!!
        val textEdits = changes["file:///test.groovy"]!!
        assertEquals(1, textEdits.size)

        val edit = textEdits[0]
        assertEquals("def x = 1", edit.newText)
    }

    @Test
    fun `should compute action to remove trailing tabs`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 0)
        val context = createContext(listOf("def x = 1\t\t"))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]
        assertEquals("def x = 1", edit.newText)
    }

    @Test
    fun `should compute action to remove mixed trailing whitespace`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 0)
        val context = createContext(listOf("def x = 1 \t \t "))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]
        assertEquals("def x = 1", edit.newText)
    }

    @Test
    fun `should handle empty line with whitespace`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 0)
        val context = createContext(listOf("   "))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]
        assertEquals("", edit.newText)
    }

    @Test
    fun `should preserve leading whitespace`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 0)
        val context = createContext(listOf("    def x = 1  "))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]
        assertEquals("    def x = 1", edit.newText)
    }

    @Test
    fun `should return null when no trailing whitespace exists`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 0)
        val context = createContext(listOf("def x = 1"))

        val action = fixer.computeAction(diagnostic, context)

        assertNull(action)
    }

    @Test
    fun `should return null for unsupported diagnostic`() {
        val diagnostic = createDiagnostic("OtherRule")
        val context = createContext(listOf("def x = 1  "))

        val action = fixer.computeAction(diagnostic, context)

        assertNull(action)
    }

    @Test
    fun `should compute fix-all action for multiple diagnostics`() {
        val diagnostics = listOf(
            createDiagnostic("TrailingWhitespace", line = 0),
            createDiagnostic("TrailingWhitespace", line = 2),
            createDiagnostic("UnnecessarySemicolon", line = 1) // Should be ignored
        )
        val context = createContext(
            listOf(
                "def x = 1  ",
                "def y = 2;",
                "def z = 3\t\t"
            )
        )

        val action = fixer.computeFixAllAction(diagnostics, context)

        assertNotNull(action)
        assertEquals("Remove all trailing whitespace", action!!.title)
        assertEquals(CodeActionKind.SourceFixAll, action.kind)
        assertEquals(2, action.diagnostics!!.size) // Only TrailingWhitespace diagnostics

        // Check that both lines are fixed
        val changes = action.edit!!.changes!!
        val textEdits = changes["file:///test.groovy"]!!
        assertEquals(2, textEdits.size)

        // Check first edit (line 0)
        val firstEdit = textEdits.find { it.range.start.line == 0 }!!
        assertEquals("def x = 1", firstEdit.newText)

        // Check second edit (line 2)
        val secondEdit = textEdits.find { it.range.start.line == 2 }!!
        assertEquals("def z = 3", secondEdit.newText)
    }

    @Test
    fun `should handle line at boundary correctly`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 1)
        val context = createContext(listOf("def x = 1", "def y = 2  "))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]
        assertEquals(Range(Position(1, 0), Position(1, 11)), edit.range)
        assertEquals("def y = 2", edit.newText)
    }

    @Test
    fun `should return null when line number is out of bounds`() {
        val diagnostic = createDiagnostic("TrailingWhitespace", line = 5)
        val context = createContext(listOf("def x = 1  "))

        val action = fixer.computeAction(diagnostic, context)

        assertNull(action)
    }

    private fun createDiagnostic(rule: String, line: Int = 0): Diagnostic {
        return Diagnostic().apply {
            range = Range(Position(line, 0), Position(line, 10))
            severity = DiagnosticSeverity.Information
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(rule)
            source = "codenarc"
            message = "Line contains trailing whitespace"
        }
    }

    private fun createContext(sourceLines: List<String>): FixContext {
        return FixContext(
            uri = "file:///test.groovy",
            document = TextDocumentIdentifier("file:///test.groovy"),
            sourceLines = sourceLines,
            compilationUnit = null,
            astCache = null,
            formattingConfig = FormattingConfig(),
            scope = FixScope.LINE
        )
    }
}
