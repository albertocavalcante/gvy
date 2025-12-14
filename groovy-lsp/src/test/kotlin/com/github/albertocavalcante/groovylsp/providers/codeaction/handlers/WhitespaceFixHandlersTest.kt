package com.github.albertocavalcante.groovylsp.providers.codeaction.handlers

import com.github.albertocavalcante.groovylsp.providers.codeaction.FixContext
import com.github.albertocavalcante.groovylsp.providers.codeaction.FixHandlerRegistry
import com.github.albertocavalcante.groovylsp.providers.codeaction.TestDiagnosticFactory
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * Tests for whitespace-related fix handlers.
 * Covers TrailingWhitespace, UnnecessarySemicolon, ConsecutiveBlankLines, and BlankLineBeforePackage.
 */
class WhitespaceFixHandlersTest {

    // ========================================================================
    // Property Tests for TrailingWhitespace
    // ========================================================================

    /**
     * Property test: Trailing Whitespace Removal
     * **Feature: codenarc-lint-fixes, Property 3: Trailing Whitespace Removal**
     * **Validates: Requirements 2.1**
     *
     * For any line containing trailing whitespace characters, applying the TrailingWhitespace fix
     * should result in a TextEdit that removes only the trailing whitespace (minimal edit).
     */
    @Property(tries = 100)
    fun `property - trailing whitespace removal produces minimal edit`(
        @ForAll("linesWithTrailingWhitespace") lineWithWhitespace: String,
    ): Boolean {
        // Skip lines that are only whitespace (edge case handled separately)
        if (lineWithWhitespace.isBlank()) return true

        val content = lineWithWhitespace
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = lineWithWhitespace.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        // Handler should always produce a fix for lines with trailing whitespace
        return if (textEdit != null) {
            // Minimal edit: newText should be empty (we're deleting, not replacing)
            textEdit.newText == ""
        } else {
            // The handler should always produce a fix for the generated lines.
            // Fail the property test if it returns null unexpectedly.
            false
        }
    }

    /**
     * Property test: Trailing Whitespace Range Correctness
     * **Feature: codenarc-lint-fixes, Property 3: Trailing Whitespace Removal**
     * **Validates: Requirements 2.1**
     *
     * The TextEdit range should cover only the trailing whitespace portion (minimal edit).
     */
    @Property(tries = 100)
    fun `property - trailing whitespace fix range covers only whitespace`(
        @ForAll("linesWithTrailingWhitespace") lineWithWhitespace: String,
    ): Boolean {
        if (lineWithWhitespace.isBlank()) return true

        val content = lineWithWhitespace
        val lines = content.lines()
        val trimmedLength = lineWithWhitespace.trimEnd().length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = lineWithWhitespace.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        return if (textEdit != null) {
            // Range should start at end of trimmed content and end at end of line
            textEdit.range.start.line == 0 &&
                textEdit.range.start.character == trimmedLength &&
                textEdit.range.end.line == 0 &&
                textEdit.range.end.character == lineWithWhitespace.length
        } else {
            // The handler should always produce a fix for the generated lines.
            // Fail the property test if it returns null unexpectedly.
            false
        }
    }

    @Provide
    fun linesWithTrailingWhitespace(): Arbitrary<String> {
        // Generate lines with various trailing whitespace patterns
        val codeContent = Arbitraries.of(
            "def x = 1",
            "println 'hello'",
            "class Foo",
            "int value = 42",
            "return result",
            "if (condition)",
            "for (item in list)",
        )
        val trailingWhitespace = Arbitraries.of(
            " ",
            "  ",
            "   ",
            "\t",
            "\t\t",
            " \t",
            "\t ",
            "    ",
        )
        return Combinators.combine(codeContent, trailingWhitespace).`as` { code, ws -> code + ws }
    }

    // ========================================================================
    // Unit Tests for TrailingWhitespace
    // ========================================================================

    @Test
    fun `trailing whitespace handler removes spaces from end of line`() {
        val content = "def x = 1   "
        val lines = content.lines()
        val trimmedLength = content.trimEnd().length // 9
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = content.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Minimal edit: delete trailing whitespace only
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(0, trimmedLength), Position(0, content.length)),
            textEdit.range,
            "Range should cover only trailing whitespace",
        )
    }

    @Test
    fun `trailing whitespace handler removes tabs from end of line`() {
        val content = "def x = 1\t\t"
        val lines = content.lines()
        val trimmedLength = content.trimEnd().length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = content.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(trimmedLength, textEdit.range.start.character, "Range should start after trimmed content")
    }

    @Test
    fun `trailing whitespace handler handles line with only whitespace`() {
        val content = "   "
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = content.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(
            handler(context),
            "Handler should return a TextEdit for whitespace-only lines",
        )

        // For whitespace-only lines, range starts at 0 (trimmed length is 0)
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(0, textEdit.range.start.character, "Range should start at 0 for whitespace-only line")
        assertEquals(content.length, textEdit.range.end.character, "Range should end at line length")
    }

    @Test
    fun `trailing whitespace handler handles mixed spaces and tabs`() {
        val content = "println 'test' \t \t"
        val lines = content.lines()
        val trimmedLength = content.trimEnd().length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = content.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(trimmedLength, textEdit.range.start.character, "Range should start after trimmed content")
    }

    @Test
    fun `trailing whitespace handler returns null for out of bounds line`() {
        val content = "def x = 1"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 5, // Out of bounds
            startChar = 0,
            endChar = 10,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    @Test
    fun `trailing whitespace handler handles multiline content`() {
        val content = "def x = 1\ndef y = 2   \ndef z = 3"
        val lines = content.lines()
        val line1 = lines[1] // "def y = 2   "
        val trimmedLength = line1.trimEnd().length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 1, // Second line has trailing whitespace
            startChar = 0,
            endChar = line1.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(1, textEdit.range.start.line, "Range should be on line 1")
        assertEquals(trimmedLength, textEdit.range.start.character, "Range should start after trimmed content")
        assertEquals(line1.length, textEdit.range.end.character, "Range should end at line length")
    }

    @Test
    fun `trailing whitespace handler returns null for line without trailing whitespace`() {
        val content = "def x = 1"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 0,
            startChar = 0,
            endChar = content.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null when no trailing whitespace exists")
    }
}
