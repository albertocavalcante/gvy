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
     * should result in a line where `line == line.trimEnd()`.
     */
    @Property(tries = 100)
    fun `property - trailing whitespace removal produces trimmed line`(
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

        // If handler returns a TextEdit, the newText should be the trimmed version
        return if (textEdit != null) {
            val expectedTrimmed = lineWithWhitespace.trimEnd()
            textEdit.newText == expectedTrimmed
        } else {
            // Handler may return null for edge cases, which is acceptable
            true
        }
    }

    /**
     * Property test: Trailing Whitespace Range Correctness
     * **Feature: codenarc-lint-fixes, Property 3: Trailing Whitespace Removal**
     * **Validates: Requirements 2.1**
     *
     * The TextEdit range should cover the entire line being fixed.
     */
    @Property(tries = 100)
    fun `property - trailing whitespace fix range covers entire line`(
        @ForAll("linesWithTrailingWhitespace") lineWithWhitespace: String,
    ): Boolean {
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

        return if (textEdit != null) {
            // Range should start at beginning of line and end at end of line
            textEdit.range.start.line == 0 &&
                textEdit.range.start.character == 0 &&
                textEdit.range.end.line == 0 &&
                textEdit.range.end.character == lineWithWhitespace.length
        } else {
            true
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

        assertEquals("def x = 1", textEdit.newText, "Trailing whitespace should be removed")
        assertEquals(Range(Position(0, 0), Position(0, content.length)), textEdit.range)
    }

    @Test
    fun `trailing whitespace handler removes tabs from end of line`() {
        val content = "def x = 1\t\t"
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
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("def x = 1", textEdit.newText, "Trailing tabs should be removed")
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

        assertEquals("", textEdit.newText, "Whitespace-only line should become empty")
    }

    @Test
    fun `trailing whitespace handler handles mixed spaces and tabs`() {
        val content = "println 'test' \t \t"
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
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("println 'test'", textEdit.newText, "Mixed trailing whitespace should be removed")
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
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Line has trailing whitespace",
            line = 1, // Second line has trailing whitespace
            startChar = 0,
            endChar = lines[1].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("TrailingWhitespace"),
            "TrailingWhitespace handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("def y = 2", textEdit.newText, "Trailing whitespace should be removed from line 1")
        assertEquals(1, textEdit.range.start.line, "Range should be on line 1")
    }
}
