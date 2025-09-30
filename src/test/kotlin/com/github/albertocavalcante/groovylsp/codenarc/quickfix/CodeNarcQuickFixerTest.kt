package com.github.albertocavalcante.groovylsp.codenarc.quickfix

import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.CodeNarcQuickFixer
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixContext
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixScope
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixerCategory
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixerMetadata
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FormattingConfig
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeNarcQuickFixerTest {

    @Test
    fun `should implement CodeNarcQuickFixer interface`() {
        val fixer = TestQuickFixer()

        assertNotNull(fixer.metadata)
        assertEquals("TestRule", fixer.metadata.ruleName)
        assertEquals(FixerCategory.FORMATTING, fixer.metadata.category)
        assertEquals(1, fixer.metadata.priority)
        assertTrue(fixer.metadata.isPreferred)
    }

    @Test
    fun `should check if fixer can handle diagnostic`() {
        val fixer = TestQuickFixer()
        val diagnostic = createTestDiagnostic("TestRule")
        val context = createTestContext()

        assertTrue(fixer.canFix(diagnostic, context))

        val wrongDiagnostic = createTestDiagnostic("WrongRule")
        assertFalse(fixer.canFix(wrongDiagnostic, context))
    }

    @Test
    fun `should compute code action for valid diagnostic`() {
        val fixer = TestQuickFixer()
        val diagnostic = createTestDiagnostic("TestRule")
        val context = createTestContext()

        val action = fixer.computeAction(diagnostic, context)

        assertNotNull(action)
        assertEquals("Fix TestRule issue", action!!.title)
        assertEquals(CodeActionKind.QuickFix, action.kind)
        assertEquals(listOf(diagnostic), action.diagnostics)
        assertTrue(action.isPreferred == true)
    }

    @Test
    fun `should return null for unsupported diagnostic`() {
        val fixer = TestQuickFixer()
        val diagnostic = createTestDiagnostic("UnsupportedRule")
        val context = createTestContext()

        val action = fixer.computeAction(diagnostic, context)

        assertNull(action)
    }

    @Test
    fun `should compute fix-all action for multiple diagnostics`() {
        val fixer = TestQuickFixer()
        val diagnostics = listOf(
            createTestDiagnostic("TestRule", line = 1),
            createTestDiagnostic("TestRule", line = 2)
        )
        val context = createTestContext()

        val action = fixer.computeFixAllAction(diagnostics, context)

        assertNotNull(action)
        assertEquals("Fix all TestRule issues", action!!.title)
        assertEquals(CodeActionKind.SourceFixAll, action.kind)
        assertEquals(diagnostics, action.diagnostics)
    }

    private fun createTestDiagnostic(ruleName: String, line: Int = 0): Diagnostic {
        return Diagnostic().apply {
            range = Range(Position(line, 0), Position(line, 10))
            severity = DiagnosticSeverity.Warning
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(ruleName)
            source = "codenarc"
            message = "Test message for $ruleName"
        }
    }

    private fun createTestContext(): FixContext {
        return FixContext(
            uri = "file:///test.groovy",
            document = TextDocumentIdentifier("file:///test.groovy"),
            sourceLines = listOf("def x = 1;", "def y = 2;"),
            compilationUnit = null,
            astCache = null,
            formattingConfig = FormattingConfig(),
            scope = FixScope.LINE
        )
    }

    // Test implementation of CodeNarcQuickFixer
    private class TestQuickFixer : CodeNarcQuickFixer {
        override val metadata = FixerMetadata(
            ruleName = "TestRule",
            category = FixerCategory.FORMATTING,
            priority = 1,
            isPreferred = true
        )

        override fun canFix(diagnostic: Diagnostic, context: FixContext): Boolean {
            return diagnostic.code.left == "TestRule"
        }

        override fun computeAction(diagnostic: Diagnostic, context: FixContext): CodeAction? {
            if (!canFix(diagnostic, context)) return null

            return CodeAction().apply {
                title = "Fix TestRule issue"
                kind = CodeActionKind.QuickFix
                diagnostics = listOf(diagnostic)
                isPreferred = true
                // Edit would be computed here
            }
        }

        override fun computeFixAllAction(diagnostics: List<Diagnostic>, context: FixContext): CodeAction? {
            val relevantDiagnostics = diagnostics.filter { canFix(it, context) }
            if (relevantDiagnostics.isEmpty()) return null

            return CodeAction().apply {
                title = "Fix all TestRule issues"
                kind = CodeActionKind.SourceFixAll
                this.diagnostics = relevantDiagnostics
                // Edit would be computed here
            }
        }
    }
}
