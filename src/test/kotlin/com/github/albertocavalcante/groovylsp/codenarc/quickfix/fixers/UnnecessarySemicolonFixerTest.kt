package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UnnecessarySemicolonFixerTest {

    private lateinit var fixer: UnnecessarySemicolonFixer

    @BeforeEach
    fun setUp() {
        fixer = UnnecessarySemicolonFixer()
    }

    @Test
    fun `should have correct metadata`() {
        val metadata = fixer.metadata

        assertEquals("UnnecessarySemicolon", metadata.ruleName)
        assertEquals(FixerCategory.FORMATTING, metadata.category)
        assertEquals(2, metadata.priority)
        assertTrue(metadata.isPreferred)
    }

    @Test
    fun `should handle UnnecessarySemicolon diagnostic`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon")
        val context = createContext(listOf("def x = 1;"))

        assertTrue(fixer.canFix(diagnostic, context))
    }

    @Test
    fun `should not handle other diagnostics`() {
        val diagnostic = createDiagnostic("TrailingWhitespace")
        val context = createContext(listOf("def x = 1;"))

        assertFalse(fixer.canFix(diagnostic, context))
    }

    @Test
    fun `should compute action to remove semicolon`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon", line = 0)
        val context = createContext(listOf("def x = 1;"))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        assertEquals("Remove unnecessary semicolon", action!!.title)
        assertEquals(CodeActionKind.QuickFix, action.kind)
        assertEquals(listOf(diagnostic), action.diagnostics)
        assertTrue(action.isPreferred == true)

        // Check the edit
        assertNotNull(action.edit)
        val changes = action.edit!!.changes!!
        assertEquals(1, changes.size)

        val textEdits = changes["file:///test.groovy"]!!
        assertEquals(1, textEdits.size)

        val edit = textEdits[0]
        assertEquals(Range(Position(0, 0), Position(0, 10)), edit.range)
        assertEquals("def x = 1", edit.newText)
    }

    @Test
    fun `should handle line with multiple semicolons carefully`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon", line = 0)
        val context = createContext(listOf("for (int i = 0; i < 10; i++);"))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]

        // Should only remove the trailing semicolon, not the ones in the for loop
        assertEquals("for (int i = 0; i < 10; i++)", edit.newText)
    }

    @Test
    fun `should handle line with semicolon in string literal`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon", line = 0)
        val context = createContext(listOf("def str = \"hello; world\";"))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]

        // Should only remove the trailing semicolon, not the one in the string
        assertEquals("def str = \"hello; world\"", edit.newText)
    }

    @Test
    fun `should handle whitespace after semicolon`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon", line = 0)
        val context = createContext(listOf("def x = 1;   "))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]

        // Should remove semicolon and trailing whitespace
        assertEquals("def x = 1", edit.newText)
    }

    @Test
    fun `should return null for unsupported diagnostic`() {
        val diagnostic = createDiagnostic("OtherRule")
        val context = createContext(listOf("def x = 1;"))

        val action = fixer.computeAction(diagnostic, context)

        assertNull(action)
    }

    @Test
    fun `should compute fix-all action for multiple diagnostics`() {
        val diagnostics = listOf(
            createDiagnostic("UnnecessarySemicolon", line = 0),
            createDiagnostic("UnnecessarySemicolon", line = 1),
            createDiagnostic("TrailingWhitespace", line = 2), // Should be ignored
        )
        val context = createContext(
            listOf(
                "def x = 1;",
                "def y = 2;",
                "def z = 3  ",
            ),
        )

        val action = fixer.computeFixAllAction(diagnostics, context)

        assertNotNull(action)
        assertEquals("Remove all unnecessary semicolons", action!!.title)
        assertEquals(CodeActionKind.SourceFixAll, action.kind)
        assertEquals(2, action.diagnostics!!.size) // Only UnnecessarySemicolon diagnostics

        // Check that both lines are fixed
        val changes = action.edit!!.changes!!
        val textEdits = changes["file:///test.groovy"]!!
        assertEquals(2, textEdits.size)
    }

    @Test
    fun `should handle empty lines gracefully`() {
        val diagnostic = createDiagnostic("UnnecessarySemicolon", line = 0)
        val context = createContext(listOf(";"))

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        val changes = action!!.edit!!.changes!!
        val edit = changes["file:///test.groovy"]!![0]
        assertEquals("", edit.newText)
    }

    private fun createDiagnostic(rule: String, line: Int = 0): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, 0), Position(line, 10))
        severity = DiagnosticSeverity.Warning
        code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(rule)
        source = "codenarc"
        message = "Unnecessary semicolon at the end of line"
    }

    private fun createContext(sourceLines: List<String>): FixContext = FixContext(
        uri = "file:///test.groovy",
        document = TextDocumentIdentifier("file:///test.groovy"),
        sourceLines = sourceLines,
        compilationUnit = null,
        astCache = null,
        formattingConfig = FormattingConfig(),
        scope = FixScope.LINE,
    )
}
