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

    // ========================================================================
    // Property Tests for UnnecessarySemicolon
    // ========================================================================

    /**
     * Property test: Semicolon Removal
     * **Feature: codenarc-lint-fixes, Property 4: Semicolon Removal**
     * **Validates: Requirements 2.2**
     *
     * For any statement ending with an unnecessary semicolon, applying the UnnecessarySemicolon fix
     * should result in a statement that does not end with a semicolon (before any trailing whitespace).
     */
    @Property(tries = 100)
    fun `property - semicolon removal removes only the semicolon`(
        @ForAll("statementsWithSemicolon") statementWithSemicolon: String,
    ): Boolean {
        val content = statementWithSemicolon
        val lines = content.lines()

        // Find the position of the semicolon (before any trailing whitespace)
        val trimmedEnd = content.trimEnd()
        val semicolonIndex = trimmedEnd.length - 1

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySemicolon",
            message = "Unnecessary semicolon",
            line = 0,
            startChar = semicolonIndex,
            endChar = semicolonIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessarySemicolon"),
            "UnnecessarySemicolon handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        return if (textEdit != null) {
            // The fix should remove only the semicolon (newText is empty)
            // and the range should cover exactly the semicolon position
            textEdit.newText == "" &&
                textEdit.range.start.character == semicolonIndex &&
                textEdit.range.end.character == semicolonIndex + 1
        } else {
            false
        }
    }

    /**
     * Property test: Semicolon Removal Preserves Trailing Whitespace
     * **Feature: codenarc-lint-fixes, Property 4: Semicolon Removal**
     * **Validates: Requirements 2.2**
     *
     * The fix should only remove the semicolon, preserving any trailing whitespace after it.
     */
    @Property(tries = 100)
    fun `property - semicolon removal preserves trailing whitespace`(
        @ForAll("statementsWithSemicolonAndTrailingWhitespace") statementWithSemicolonAndWs: String,
    ): Boolean {
        val content = statementWithSemicolonAndWs
        val lines = content.lines()

        // Find the semicolon position (it's right before the trailing whitespace)
        val trimmedEnd = content.trimEnd()
        val semicolonIndex = trimmedEnd.length - 1

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySemicolon",
            message = "Unnecessary semicolon",
            line = 0,
            startChar = semicolonIndex,
            endChar = semicolonIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessarySemicolon"),
            "UnnecessarySemicolon handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        return if (textEdit != null) {
            // The range should only cover the semicolon, not the trailing whitespace
            textEdit.range.end.character == semicolonIndex + 1
        } else {
            false
        }
    }

    @Provide
    fun statementsWithSemicolon(): Arbitrary<String> {
        // Generate statements that end with a semicolon (no trailing whitespace)
        val statements = Arbitraries.of(
            "def x = 1",
            "println 'hello'",
            "int value = 42",
            "return result",
            "x++",
            "list.add(item)",
            "map.put(key, value)",
        )
        return statements.map { "$it;" }
    }

    @Provide
    fun statementsWithSemicolonAndTrailingWhitespace(): Arbitrary<String> {
        // Generate statements with semicolon followed by trailing whitespace
        val statements = Arbitraries.of(
            "def x = 1",
            "println 'hello'",
            "int value = 42",
            "return result",
            "x++",
        )
        val trailingWhitespace = Arbitraries.of(
            " ",
            "  ",
            "\t",
            "   ",
        )
        return Combinators.combine(statements, trailingWhitespace).`as` { stmt, ws -> "$stmt;$ws" }
    }

    // ========================================================================
    // Unit Tests for UnnecessarySemicolon
    // ========================================================================

    @Test
    fun `semicolon handler removes semicolon from end of statement`() {
        val content = "def x = 1;"
        val lines = content.lines()
        val semicolonIndex = content.length - 1 // Position of ';'
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySemicolon",
            message = "Unnecessary semicolon",
            line = 0,
            startChar = semicolonIndex,
            endChar = semicolonIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessarySemicolon"),
            "UnnecessarySemicolon handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(0, semicolonIndex), Position(0, semicolonIndex + 1)),
            textEdit.range,
            "Range should cover only the semicolon",
        )
    }

    @Test
    fun `semicolon handler preserves trailing whitespace`() {
        val content = "def x = 1;  "
        val lines = content.lines()
        val semicolonIndex = 9 // Position of ';' (after "def x = 1")
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySemicolon",
            message = "Unnecessary semicolon",
            line = 0,
            startChar = semicolonIndex,
            endChar = semicolonIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessarySemicolon"),
            "UnnecessarySemicolon handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        // Range should only cover the semicolon, not the trailing whitespace
        assertEquals(
            Range(Position(0, semicolonIndex), Position(0, semicolonIndex + 1)),
            textEdit.range,
            "Range should cover only the semicolon, preserving trailing whitespace",
        )
    }

    @Test
    fun `semicolon handler returns null for out of bounds line`() {
        val content = "def x = 1;"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySemicolon",
            message = "Unnecessary semicolon",
            line = 5, // Out of bounds
            startChar = 9,
            endChar = 10,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessarySemicolon"),
            "UnnecessarySemicolon handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    @Test
    fun `semicolon handler handles multiline content`() {
        val content = "def x = 1\ndef y = 2;\ndef z = 3"
        val lines = content.lines()
        val line1 = lines[1] // "def y = 2;"
        val semicolonIndex = line1.length - 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySemicolon",
            message = "Unnecessary semicolon",
            line = 1, // Second line has the semicolon
            startChar = semicolonIndex,
            endChar = semicolonIndex + 1,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessarySemicolon"),
            "UnnecessarySemicolon handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(1, textEdit.range.start.line, "Range should be on line 1")
        assertEquals(semicolonIndex, textEdit.range.start.character, "Range should start at semicolon")
        assertEquals(semicolonIndex + 1, textEdit.range.end.character, "Range should end after semicolon")
    }

    // ========================================================================
    // Property Tests for ConsecutiveBlankLines
    // ========================================================================

    /**
     * Property test: Consecutive Blank Lines Reduction
     * **Feature: codenarc-lint-fixes, Property 5: Consecutive Blank Lines Reduction**
     * **Validates: Requirements 2.3**
     *
     * For any sequence of N consecutive blank lines where N >= 2, applying the ConsecutiveBlankLines fix
     * should result in exactly 1 blank line.
     */
    @Property(tries = 100)
    fun `property - consecutive blank lines reduction produces single blank line`(
        @ForAll("contentWithConsecutiveBlankLines") contentWithBlankLines: ContentWithBlankLinesInfo,
    ): Boolean {
        val content = contentWithBlankLines.content
        val lines = content.lines()
        val blankLinesStartLine = contentWithBlankLines.blankLinesStartLine
        val blankLinesCount = contentWithBlankLines.blankLinesCount

        // The diagnostic range covers from the start of the first blank line to the end of the last blank line
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ConsecutiveBlankLines",
            message = "File has $blankLinesCount consecutive blank lines",
            line = blankLinesStartLine,
            startChar = 0,
            endChar = 0,
        )

        val handler = FixHandlerRegistry.getHandler("ConsecutiveBlankLines") ?: return false

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context) ?: return false

        // The fix should reduce N blank lines to 1 blank line
        // This means removing (N-1) blank lines
        // The range should span from the second blank line to the end of the last blank line
        // Verify the range covers the correct number of lines to remove
        val startLine = textEdit.range.start.line
        val endLine = textEdit.range.end.line

        // The edit should remove (blankLinesCount - 1) lines
        // Start at the second blank line (blankLinesStartLine + 1)
        // End at the last blank line (blankLinesStartLine + blankLinesCount - 1)
        return startLine == blankLinesStartLine + 1 &&
            endLine == blankLinesStartLine + blankLinesCount &&
            textEdit.newText == ""
    }

    /**
     * Data class to hold content with consecutive blank lines and metadata about them.
     */
    data class ContentWithBlankLinesInfo(val content: String, val blankLinesStartLine: Int, val blankLinesCount: Int)

    @Provide
    fun contentWithConsecutiveBlankLines(): Arbitrary<ContentWithBlankLinesInfo> {
        // Generate content with consecutive blank lines
        val beforeLines = Arbitraries.of(
            "def x = 1",
            "class Foo {",
            "println 'hello'",
            "int value = 42",
        )
        val afterLines = Arbitraries.of(
            "def y = 2",
            "}",
            "println 'world'",
            "return result",
        )
        val blankLineCount = Arbitraries.integers().between(2, 5)

        return Combinators.combine(beforeLines, blankLineCount, afterLines).`as` { before, count, after ->
            val blankLines = "\n".repeat(count)
            val content = "$before\n$blankLines$after"
            ContentWithBlankLinesInfo(
                content = content,
                blankLinesStartLine = 1, // Blank lines start after the first line
                blankLinesCount = count,
            )
        }
    }

    // ========================================================================
    // Unit Tests for ConsecutiveBlankLines
    // ========================================================================

    @Test
    fun `consecutive blank lines handler reduces 3 blank lines to 1`() {
        val content = "def x = 1\n\n\n\ndef y = 2"
        val lines = content.lines()
        // Lines: ["def x = 1", "", "", "", "def y = 2"]
        // Blank lines are at indices 1, 2, 3 (3 blank lines)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ConsecutiveBlankLines",
            message = "File has 3 consecutive blank lines",
            line = 1, // First blank line
            startChar = 0,
            endChar = 0,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("ConsecutiveBlankLines"),
            "ConsecutiveBlankLines handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove 2 blank lines (keep 1), so range should cover lines 2-3 (inclusive of newline)
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(2, textEdit.range.start.line, "Range should start at second blank line")
        assertEquals(0, textEdit.range.start.character, "Range should start at beginning of line")
        assertEquals(4, textEdit.range.end.line, "Range should end at line after last blank line")
        assertEquals(0, textEdit.range.end.character, "Range should end at beginning of next line")
    }

    @Test
    fun `consecutive blank lines handler reduces 2 blank lines to 1`() {
        val content = "def x = 1\n\n\ndef y = 2"
        val lines = content.lines()
        // Lines: ["def x = 1", "", "", "def y = 2"]
        // Blank lines are at indices 1, 2 (2 blank lines)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ConsecutiveBlankLines",
            message = "File has 2 consecutive blank lines",
            line = 1, // First blank line
            startChar = 0,
            endChar = 0,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("ConsecutiveBlankLines"),
            "ConsecutiveBlankLines handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove 1 blank line (keep 1), so range should cover line 2
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(2, textEdit.range.start.line, "Range should start at second blank line")
        assertEquals(0, textEdit.range.start.character, "Range should start at beginning of line")
        assertEquals(3, textEdit.range.end.line, "Range should end at line after last blank line")
        assertEquals(0, textEdit.range.end.character, "Range should end at beginning of next line")
    }

    @Test
    fun `consecutive blank lines handler returns null for out of bounds line`() {
        val content = "def x = 1"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ConsecutiveBlankLines",
            message = "File has 2 consecutive blank lines",
            line = 10, // Out of bounds
            startChar = 0,
            endChar = 0,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("ConsecutiveBlankLines"),
            "ConsecutiveBlankLines handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    @Test
    fun `consecutive blank lines handler handles blank lines at end of file`() {
        val content = "def x = 1\n\n\n"
        val lines = content.lines()
        // Lines: ["def x = 1", "", "", ""]
        // Blank lines are at indices 1, 2, 3 (3 blank lines total)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ConsecutiveBlankLines",
            message = "File has 3 consecutive blank lines",
            line = 1, // First blank line
            startChar = 0,
            endChar = 0,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("ConsecutiveBlankLines"),
            "ConsecutiveBlankLines handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove 2 blank lines (keep 1), range covers lines 2-3
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(2, textEdit.range.start.line, "Range should start at second blank line")
        assertEquals(4, textEdit.range.end.line, "Range should end at line after last blank line")
    }

    @Test
    fun `consecutive blank lines handler handles blank lines at start of file`() {
        val content = "\n\n\ndef x = 1"
        val lines = content.lines()
        // Lines: ["", "", "", "def x = 1"]
        // Blank lines are at indices 0, 1, 2 (3 blank lines)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ConsecutiveBlankLines",
            message = "File has 3 consecutive blank lines",
            line = 0, // First blank line
            startChar = 0,
            endChar = 0,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("ConsecutiveBlankLines"),
            "ConsecutiveBlankLines handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove 2 blank lines (keep 1)
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(1, textEdit.range.start.line, "Range should start at second blank line")
        assertEquals(3, textEdit.range.end.line, "Range should end at line after last blank line")
    }

    // ========================================================================
    // Unit Tests for BlankLineBeforePackage
    // ========================================================================

    @Test
    fun `blank line before package handler removes single blank line before package`() {
        val content = "\npackage com.example"
        val lines = content.lines()
        // Lines: ["", "package com.example"]
        // Blank line at index 0, package at index 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "BlankLineBeforePackage",
            message = "File has blank line(s) before the package statement",
            line = 1, // Package statement line
            startChar = 0,
            endChar = 19,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("BlankLineBeforePackage"),
            "BlankLineBeforePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove the blank line before package
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(0, textEdit.range.start.line, "Range should start at first blank line")
        assertEquals(0, textEdit.range.start.character, "Range should start at beginning of line")
        assertEquals(1, textEdit.range.end.line, "Range should end at package line")
        assertEquals(0, textEdit.range.end.character, "Range should end at beginning of package line")
    }

    @Test
    fun `blank line before package handler removes multiple blank lines before package`() {
        val content = "\n\n\npackage com.example"
        val lines = content.lines()
        // Lines: ["", "", "", "package com.example"]
        // Blank lines at indices 0, 1, 2, package at index 3
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "BlankLineBeforePackage",
            message = "File has blank line(s) before the package statement",
            line = 3, // Package statement line
            startChar = 0,
            endChar = 19,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("BlankLineBeforePackage"),
            "BlankLineBeforePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove all blank lines before package
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(0, textEdit.range.start.line, "Range should start at first blank line")
        assertEquals(0, textEdit.range.start.character, "Range should start at beginning of line")
        assertEquals(3, textEdit.range.end.line, "Range should end at package line")
        assertEquals(0, textEdit.range.end.character, "Range should end at beginning of package line")
    }

    @Test
    fun `blank line before package handler handles whitespace-only lines before package`() {
        val content = "   \n\t\npackage com.example"
        val lines = content.lines()
        // Lines: ["   ", "\t", "package com.example"]
        // Whitespace-only lines at indices 0, 1, package at index 2
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "BlankLineBeforePackage",
            message = "File has blank line(s) before the package statement",
            line = 2, // Package statement line
            startChar = 0,
            endChar = 19,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("BlankLineBeforePackage"),
            "BlankLineBeforePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove all whitespace-only lines before package
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(0, textEdit.range.start.line, "Range should start at first blank line")
        assertEquals(0, textEdit.range.start.character, "Range should start at beginning of line")
        assertEquals(2, textEdit.range.end.line, "Range should end at package line")
        assertEquals(0, textEdit.range.end.character, "Range should end at beginning of package line")
    }

    @Test
    fun `blank line before package handler returns null when package is at line 0`() {
        val content = "package com.example"
        val lines = content.lines()
        // Lines: ["package com.example"]
        // Package at index 0, no blank lines before
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "BlankLineBeforePackage",
            message = "File has blank line(s) before the package statement",
            line = 0, // Package statement line
            startChar = 0,
            endChar = 19,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("BlankLineBeforePackage"),
            "BlankLineBeforePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null when package is at line 0")
    }

    @Test
    fun `blank line before package handler returns null for out of bounds line`() {
        val content = "package com.example"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "BlankLineBeforePackage",
            message = "File has blank line(s) before the package statement",
            line = 10, // Out of bounds
            startChar = 0,
            endChar = 19,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("BlankLineBeforePackage"),
            "BlankLineBeforePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    @Test
    fun `blank line before package handler returns null when no blank lines before package`() {
        val content = "// Comment\npackage com.example"
        val lines = content.lines()
        // Lines: ["// Comment", "package com.example"]
        // Comment at index 0, package at index 1 - no blank lines
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "BlankLineBeforePackage",
            message = "File has blank line(s) before the package statement",
            line = 1, // Package statement line
            startChar = 0,
            endChar = 19,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("BlankLineBeforePackage"),
            "BlankLineBeforePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null when no blank lines before package")
    }
}
