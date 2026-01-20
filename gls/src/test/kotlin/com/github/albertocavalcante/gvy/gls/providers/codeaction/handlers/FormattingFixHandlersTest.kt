package com.github.albertocavalcante.gvy.gls.providers.codeaction.handlers

import com.github.albertocavalcante.gvy.gls.providers.codeaction.FixContext
import com.github.albertocavalcante.gvy.gls.providers.codeaction.FixHandlerRegistry
import com.github.albertocavalcante.gvy.gls.providers.codeaction.TestDiagnosticFactory
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * Tests for formatting-related fix handlers.
 * Covers SpaceBeforeOpeningBrace.
 */
class FormattingFixHandlersTest {

    // ========================================================================
    // Unit Tests for SpaceBeforeOpeningBrace
    // ========================================================================

    @Test
    fun `space before brace handler adds space in method declaration`() {
        val content = "def foo(){}"
        val lines = content.lines()
        val braceIndex = 9 // Position of '{'
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 0,
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should insert a space before the brace
        assertEquals(" ", textEdit.newText, "newText should be a single space")
        assertEquals(
            Range(Position(0, braceIndex), Position(0, braceIndex)),
            textEdit.range,
            "Range should be zero-width at the brace position",
        )
    }

    @Test
    fun `space before brace handler adds space in closure`() {
        val content = "list.each{}"
        val lines = content.lines()
        val braceIndex = 9 // Position of '{'
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 0,
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals(" ", textEdit.newText, "newText should be a single space")
        assertEquals(
            Range(Position(0, braceIndex), Position(0, braceIndex)),
            textEdit.range,
            "Range should be zero-width at the brace position",
        )
    }

    @Test
    fun `space before brace handler adds space in if statement`() {
        val content = "if(x){}"
        val lines = content.lines()
        val braceIndex = 5 // Position of '{'
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 0,
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals(" ", textEdit.newText, "newText should be a single space")
        assertEquals(
            Range(Position(0, braceIndex), Position(0, braceIndex)),
            textEdit.range,
            "Range should be zero-width at the brace position",
        )
    }

    @Test
    fun `space before brace handler adds space in class declaration`() {
        val content = "class Foo{}"
        val lines = content.lines()
        val braceIndex = 9 // Position of '{'
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 0,
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals(" ", textEdit.newText, "newText should be a single space")
        assertEquals(
            Range(Position(0, braceIndex), Position(0, braceIndex)),
            textEdit.range,
            "Range should be zero-width at the brace position",
        )
    }

    @Test
    fun `space before brace handler returns null when space already exists`() {
        val content = "def foo() {}"
        val lines = content.lines()
        val braceIndex = 10 // Position of '{'
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 0,
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null when space already exists")
    }

    @Test
    fun `space before brace handler returns null when tab exists before brace`() {
        val content = "def foo()\t{}"
        val lines = content.lines()
        val braceIndex = content.indexOf('{')
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "Missing space before brace",
            line = 0,
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null when tab exists before brace")
    }

    @Test
    fun `space before brace handler returns null for out of bounds line`() {
        val content = "def foo(){}"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 99, // Out of bounds
            startChar = 8,
            endChar = 9,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    @Test
    fun `space before brace handler handles multiline content`() {
        val content = "line1\ndef foo(){}\nline3"
        val lines = content.lines()
        val braceIndex = 9 // Position of '{' on line 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SpaceBeforeOpeningBrace",
            message = "The opening brace is not preceded by a space",
            line = 1, // Second line has the brace
            startChar = braceIndex,
            endChar = braceIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("SpaceBeforeOpeningBrace"),
            "SpaceBeforeOpeningBrace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals(" ", textEdit.newText, "newText should be a single space")
        assertEquals(1, textEdit.range.start.line, "Range should be on line 1")
        assertEquals(braceIndex, textEdit.range.start.character, "Range should start at brace position")
        assertEquals(braceIndex, textEdit.range.end.character, "Range should end at brace position")
    }
}
