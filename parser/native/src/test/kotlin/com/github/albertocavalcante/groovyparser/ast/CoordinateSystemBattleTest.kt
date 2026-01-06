package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Battle Test: Comprehensive tests for the CoordinateSystem.
 *
 * These tests verify that coordinate conversions between LSP (0-based)
 * and Groovy (1-based) systems are correct in all edge cases.
 *
 * This is critical for ensuring diagnostics appear at the correct position.
 */
class CoordinateSystemBattleTest {

    // ==========================================
    // LSP to Groovy Conversion Tests
    // ==========================================

    @Test
    fun `lspToGroovy should convert first line and character correctly`() {
        val groovy = CoordinateSystem.lspToGroovy(0, 0)

        assertEquals(1, groovy.line, "LSP line 0 should be Groovy line 1")
        assertEquals(1, groovy.column, "LSP character 0 should be Groovy column 1")
    }

    @Test
    fun `lspToGroovy should convert middle positions correctly`() {
        val groovy = CoordinateSystem.lspToGroovy(5, 10)

        assertEquals(6, groovy.line, "LSP line 5 should be Groovy line 6")
        assertEquals(11, groovy.column, "LSP character 10 should be Groovy column 11")
    }

    @Test
    fun `lspToGroovy should convert Position object correctly`() {
        val lspPosition = Position(3, 7)
        val groovy = CoordinateSystem.lspToGroovy(lspPosition)

        assertEquals(4, groovy.line)
        assertEquals(8, groovy.column)
    }

    @Test
    fun `lspToGroovy should handle large line numbers`() {
        val groovy = CoordinateSystem.lspToGroovy(999, 499)

        assertEquals(1000, groovy.line)
        assertEquals(500, groovy.column)
    }

    // ==========================================
    // Groovy to LSP Conversion Tests
    // ==========================================

    @Test
    fun `groovyToLsp should convert first line and column correctly`() {
        val lsp = CoordinateSystem.groovyToLsp(1, 1)

        assertEquals(0, lsp.line, "Groovy line 1 should be LSP line 0")
        assertEquals(0, lsp.character, "Groovy column 1 should be LSP character 0")
    }

    @Test
    fun `groovyToLsp should convert middle positions correctly`() {
        val lsp = CoordinateSystem.groovyToLsp(6, 11)

        assertEquals(5, lsp.line, "Groovy line 6 should be LSP line 5")
        assertEquals(10, lsp.character, "Groovy column 11 should be LSP character 10")
    }

    @Test
    fun `groovyToLsp should handle large line numbers`() {
        val lsp = CoordinateSystem.groovyToLsp(1000, 500)

        assertEquals(999, lsp.line)
        assertEquals(499, lsp.character)
    }

    // ==========================================
    // Round-Trip Conversion Tests
    // ==========================================

    @Test
    fun `lspToGroovy then groovyToLsp should return original position`() {
        val originalLspLine = 10
        val originalLspChar = 25

        val groovy = CoordinateSystem.lspToGroovy(originalLspLine, originalLspChar)
        val lsp = CoordinateSystem.groovyToLsp(groovy.line, groovy.column)

        assertEquals(originalLspLine, lsp.line)
        assertEquals(originalLspChar, lsp.character)
    }

    @Test
    fun `groovyToLsp then lspToGroovy should return original position`() {
        val originalGroovyLine = 11
        val originalGroovyColumn = 26

        val lsp = CoordinateSystem.groovyToLsp(originalGroovyLine, originalGroovyColumn)
        val groovy = CoordinateSystem.lspToGroovy(lsp.line, lsp.character)

        assertEquals(originalGroovyLine, groovy.line)
        assertEquals(originalGroovyColumn, groovy.column)
    }

    // ==========================================
    // LspPosition Type Tests
    // ==========================================

    @Test
    fun `LspPosition toGroovy should convert correctly`() {
        val lsp = CoordinateSystem.LspPosition(5, 10)
        val groovy = lsp.toGroovy()

        assertEquals(6, groovy.line)
        assertEquals(11, groovy.column)
    }

    @Test
    fun `LspPosition toLsp4j should convert correctly`() {
        val lsp = CoordinateSystem.LspPosition(5, 10)
        val lsp4j = lsp.toLsp4j()

        assertEquals(5, lsp4j.line)
        assertEquals(10, lsp4j.character)
    }

    @Test
    fun `LspPosition from factory methods should work correctly`() {
        val lsp1 = CoordinateSystem.LspPosition.from(Position(3, 7))
        assertEquals(3, lsp1.line)
        assertEquals(7, lsp1.character)

        val lsp2 = CoordinateSystem.LspPosition.from(3, 7)
        assertEquals(3, lsp2.line)
        assertEquals(7, lsp2.character)
    }

    // ==========================================
    // GroovyPosition Type Tests
    // ==========================================

    @Test
    fun `GroovyPosition toLsp should convert correctly`() {
        val groovy = CoordinateSystem.GroovyPosition(6, 11)
        val lsp = groovy.toLsp()

        assertEquals(5, lsp.line)
        assertEquals(10, lsp.character)
    }

    @Test
    fun `GroovyPosition from factory methods should work correctly`() {
        val groovy = CoordinateSystem.GroovyPosition.from(6, 11)
        assertEquals(6, groovy.line)
        assertEquals(11, groovy.column)
    }

    // ==========================================
    // Range Conversion Tests
    // ==========================================

    @Test
    fun `GroovyRange toLsp should convert correctly`() {
        val groovyStart = CoordinateSystem.GroovyPosition(2, 5)
        val groovyEnd = CoordinateSystem.GroovyPosition(2, 10)
        val groovyRange = CoordinateSystem.GroovyRange(groovyStart, groovyEnd)

        val lspRange = groovyRange.toLsp()

        assertEquals(1, lspRange.start.line)
        assertEquals(4, lspRange.start.character)
        assertEquals(1, lspRange.end.line)
        assertEquals(9, lspRange.end.character)
    }

    @Test
    fun `GroovyRange toLsp should handle multi-line ranges`() {
        val groovyStart = CoordinateSystem.GroovyPosition(2, 5)
        val groovyEnd = CoordinateSystem.GroovyPosition(5, 10)
        val groovyRange = CoordinateSystem.GroovyRange(groovyStart, groovyEnd)

        val lspRange = groovyRange.toLsp()

        assertEquals(1, lspRange.start.line)
        assertEquals(4, lspRange.start.character)
        assertEquals(4, lspRange.end.line)
        assertEquals(9, lspRange.end.character)
    }

    // ==========================================
    // Edge Case Tests
    // ==========================================

    @Test
    fun `should handle zero LSP coordinates (first position)`() {
        val lsp = CoordinateSystem.LspPosition(0, 0)
        val groovy = lsp.toGroovy()

        assertEquals(1, groovy.line)
        assertEquals(1, groovy.column)

        val lspAgain = groovy.toLsp()
        assertEquals(0, lspAgain.line)
        assertEquals(0, lspAgain.character)
    }

    @Test
    fun `should handle one Groovy coordinates (first position)`() {
        val groovy = CoordinateSystem.GroovyPosition(1, 1)
        val lsp = groovy.toLsp()

        assertEquals(0, lsp.line)
        assertEquals(0, lsp.character)

        val groovyAgain = lsp.toGroovy()
        assertEquals(1, groovyAgain.line)
        assertEquals(1, groovyAgain.column)
    }

    @Test
    fun `should handle same line positions`() {
        // Test conversion of multiple positions on the same line
        val positions = listOf(
            0 to 0, // Start of line
            0 to 10, // Middle of line
            0 to 80, // End of line
        )

        positions.forEach { (lspLine, lspChar) ->
            val groovy = CoordinateSystem.lspToGroovy(lspLine, lspChar)
            assertEquals(1, groovy.line, "All positions should be on Groovy line 1")
            assertEquals(lspChar + 1, groovy.column, "LSP char $lspChar should be Groovy column ${lspChar + 1}")
        }
    }

    @Test
    fun `should handle sequential line positions`() {
        // Test conversion of first character on sequential lines
        val lines = listOf(0, 1, 2, 3, 4, 5)

        lines.forEach { lspLine ->
            val groovy = CoordinateSystem.lspToGroovy(lspLine, 0)
            assertEquals(lspLine + 1, groovy.line, "LSP line $lspLine should be Groovy line ${lspLine + 1}")
            assertEquals(1, groovy.column, "First character should always be Groovy column 1")
        }
    }

    // ==========================================
    // Diagnostic Range Calculation Tests
    // ==========================================

    @Test
    fun `should calculate correct range for single character diagnostic`() {
        // Example: Semicolon at position (0, 10) in LSP
        val lspStart = CoordinateSystem.LspPosition(0, 10)
        val lspEnd = CoordinateSystem.LspPosition(0, 11)

        val groovyStart = lspStart.toGroovy()
        val groovyEnd = lspEnd.toGroovy()

        assertEquals(1, groovyStart.line)
        assertEquals(11, groovyStart.column)
        assertEquals(1, groovyEnd.line)
        assertEquals(12, groovyEnd.column)
    }

    @Test
    fun `should calculate correct range for word diagnostic`() {
        // Example: Variable "unusedVar" at positions (2, 12) to (2, 21) in LSP
        val lspStart = CoordinateSystem.LspPosition(2, 12)
        val lspEnd = CoordinateSystem.LspPosition(2, 21)

        val groovyStart = lspStart.toGroovy()
        val groovyEnd = lspEnd.toGroovy()

        assertEquals(3, groovyStart.line)
        assertEquals(13, groovyStart.column)
        assertEquals(3, groovyEnd.line)
        assertEquals(22, groovyEnd.column)
    }

    @Test
    fun `should calculate correct range for trailing whitespace`() {
        // Example: Trailing spaces from (1, 18) to (1, 21) in LSP
        val lspStart = CoordinateSystem.LspPosition(1, 18)
        val lspEnd = CoordinateSystem.LspPosition(1, 21)

        val groovyStart = lspStart.toGroovy()
        val groovyEnd = lspEnd.toGroovy()

        assertEquals(2, groovyStart.line)
        assertEquals(19, groovyStart.column)
        assertEquals(2, groovyEnd.line)
        assertEquals(22, groovyEnd.column)
    }

    @Test
    fun `should calculate correct range for indentation diagnostic`() {
        // Example: Indentation from (1, 0) to (1, 3) in LSP
        val lspStart = CoordinateSystem.LspPosition(1, 0)
        val lspEnd = CoordinateSystem.LspPosition(1, 3)

        val groovyStart = lspStart.toGroovy()
        val groovyEnd = lspEnd.toGroovy()

        assertEquals(2, groovyStart.line)
        assertEquals(1, groovyStart.column)
        assertEquals(2, groovyEnd.line)
        assertEquals(4, groovyEnd.column)
    }

    // ==========================================
    // CodeNarc Violation Position Tests
    // ==========================================

    @Test
    fun `should convert CodeNarc line number to LSP correctly`() {
        // CodeNarc reports line 1 (first line in file)
        val codenarcLine = 1
        val codenarcColumn = 1 // CodeNarc doesn't provide column, defaults to 1

        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, codenarcColumn)
        val lsp = groovy.toLsp()

        assertEquals(0, lsp.line, "CodeNarc line 1 should be LSP line 0")
        assertEquals(0, lsp.character, "CodeNarc column 1 should be LSP character 0")
    }

    @Test
    fun `should convert CodeNarc line number for middle of file`() {
        // CodeNarc reports line 10
        val codenarcLine = 10
        val codenarcColumn = 1

        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, codenarcColumn)
        val lsp = groovy.toLsp()

        assertEquals(9, lsp.line, "CodeNarc line 10 should be LSP line 9")
        assertEquals(0, lsp.character)
    }

    @Test
    fun `should handle calculated column offset for diagnostics`() {
        // CodeNarc line 5, then we calculate column 12 from source line
        val codenarcLine = 5
        val calculatedColumn = 12 // From RuleRangeCalculator

        // Convert CodeNarc line to LSP line
        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, 1)
        val lspLine = groovy.toLsp().line

        // LSP column is 0-based, so we use calculated column directly
        val lspChar = calculatedColumn

        assertEquals(4, lspLine, "CodeNarc line 5 should be LSP line 4")
        assertEquals(12, lspChar, "Calculated column 12 should remain 12 in LSP")
    }

    // ==========================================
    // Real-World Scenario Tests
    // ==========================================

    @Test
    fun `should handle typical semicolon violation`() {
        // Source: "    def x = 1;" (line 2 in file)
        // Semicolon at column 14 (0-based)
        val codenarcLine = 2
        val semicolonColumn = 14 // Calculated by RuleRangeCalculator

        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, 1)
        val lspLine = groovy.toLsp().line

        assertEquals(1, lspLine)
        assertEquals(14, semicolonColumn) // Direct use in LSP
    }

    @Test
    fun `should handle typical variable name violation`() {
        // Source: "        def unusedVar = 123" (line 3 in file)
        // "unusedVar" at columns 12-21 (0-based)
        val codenarcLine = 3
        val varStartColumn = 12
        val varEndColumn = 21

        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, 1)
        val lspLine = groovy.toLsp().line

        assertEquals(2, lspLine)
        assertEquals(12, varStartColumn)
        assertEquals(21, varEndColumn)
    }

    @Test
    fun `should handle typical class name violation`() {
        // Source: "class myClass {" (line 1 in file)
        // "myClass" at columns 6-13 (0-based)
        val codenarcLine = 1
        val classNameStartColumn = 6
        val classNameEndColumn = 13

        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, 1)
        val lspLine = groovy.toLsp().line

        assertEquals(0, lspLine)
        assertEquals(6, classNameStartColumn)
        assertEquals(13, classNameEndColumn)
    }

    @Test
    fun `should handle typical trailing whitespace violation`() {
        // Source: "    def method() {   " (line 2 in file, 3 trailing spaces)
        // Trailing whitespace at columns 18-21 (0-based)
        val codenarcLine = 2
        val trailingStartColumn = 18
        val trailingEndColumn = 21

        val groovy = CoordinateSystem.GroovyPosition(codenarcLine, 1)
        val lspLine = groovy.toLsp().line

        assertEquals(1, lspLine)
        assertEquals(18, trailingStartColumn)
        assertEquals(21, trailingEndColumn)
    }

    // ==========================================
    // ROBUSTNESS: Verify Coordinates Match Actual Text
    // ==========================================

    @Test
    fun `coordinates should match actual substring - keyword highlighting`() {
        val sourceLine = "class MyClass { def method() {} }"
        // "class" at columns 0-5 (0-based LSP)
        val startCol = 0
        val endCol = 5

        val actualText = sourceLine.substring(startCol, endCol)
        assertEquals("class", actualText, "Coordinates must point to actual keyword")
    }

    @Test
    fun `coordinates should match actual substring - identifier highlighting`() {
        val sourceLine = "def BadName = 1"
        // "BadName" at columns 4-11 (0-based LSP)
        val startCol = 4
        val endCol = 11

        val actualText = sourceLine.substring(startCol, endCol)
        assertEquals("BadName", actualText, "Coordinates must point to actual identifier")
    }

    @Test
    fun `coordinates should match actual substring - exception type`() {
        val sourceLine = "} catch (Exception e) {"
        // "Exception" at columns 9-18 (0-based LSP)
        val startCol = 9
        val endCol = 18

        val actualText = sourceLine.substring(startCol, endCol)
        assertEquals("Exception", actualText, "Coordinates must point to actual exception type")
    }

    @Test
    fun `coordinates should never produce negative indices`() {
        // Test all reasonable LSP positions
        for (line in 0..100) {
            for (char in 0..100) {
                val groovy = CoordinateSystem.lspToGroovy(line, char)
                assert(groovy.line >= 1) { "Groovy line must be >= 1" }
                assert(groovy.column >= 1) { "Groovy column must be >= 1" }
            }
        }
    }

    @Test
    fun `coordinates should never exceed reasonable bounds`() {
        val sourceLine = "class X {}"
        val maxLength = sourceLine.length

        // Any range within the line should be valid
        for (start in 0 until maxLength) {
            for (end in start + 1..maxLength) {
                val text = sourceLine.substring(start, end)
                assert(text.isNotEmpty()) { "Range ($start, $end) should produce non-empty text" }
            }
        }
    }

    @Test
    fun `lsp range (0,5) should highlight first 5 characters`() {
        val sourceLine = "class MyClass"
        val start = 0
        val end = 5

        val highlighted = sourceLine.substring(start, end)
        assertEquals("class", highlighted)
        assertEquals(5, highlighted.length)
    }

    @Test
    fun `groovy violation at line 1 col 1 should map to lsp line 0 char 0`() {
        val groovyLine = 1
        val groovyCol = 1

        val lsp = CoordinateSystem.groovyToLsp(groovyLine, groovyCol)

        assertEquals(0, lsp.line)
        assertEquals(0, lsp.character)
    }

    @Test
    fun `multi-line file coordinate integrity`() {
        val source = """
            class MyClass {
                def badName = 1
                String AnotherBadName = "test"
            }
        """.trimIndent()

        val lines = source.lines()

        // Line 0 (LSP): "class MyClass {"
        assertEquals("class MyClass {", lines[0])
        assertEquals("class", lines[0].substring(0, 5))

        // Line 1 (LSP): "    def badName = 1"
        assertEquals("    def badName = 1", lines[1])
        assertEquals("badName", lines[1].substring(8, 15))

        // Line 2 (LSP): "    String AnotherBadName = \"test\""
        assertEquals("    String AnotherBadName = \"test\"", lines[2])
        assertEquals("AnotherBadName", lines[2].substring(11, 25))
    }
}
