package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the CoordinateSystem singleton.
 * These tests verify coordinate conversion and position containment logic.
 */
class CoordinateSystemTest {

    private lateinit var parserFacade: GroovyParserFacade

    @BeforeEach
    fun setup() {
        parserFacade = GroovyParserFacade()
    }

    @Test
    fun `lspToGroovy converts coordinates correctly`() {
        // LSP coordinates are 0-based, Groovy are 1-based
        val groovyPos = CoordinateSystem.lspToGroovy(0, 0)
        assertEquals(1, groovyPos.line)
        assertEquals(1, groovyPos.column)

        val groovyPos2 = CoordinateSystem.lspToGroovy(5, 10)
        assertEquals(6, groovyPos2.line)
        assertEquals(11, groovyPos2.column)
    }

    @Test
    fun `groovyToLsp converts coordinates correctly`() {
        // Groovy coordinates are 1-based, LSP are 0-based
        val lspPos = CoordinateSystem.groovyToLsp(1, 1)
        assertEquals(0, lspPos.line)
        assertEquals(0, lspPos.character)

        val lspPos2 = CoordinateSystem.groovyToLsp(6, 11)
        assertEquals(5, lspPos2.line)
        assertEquals(10, lspPos2.character)
    }

    @Test
    fun `LspPosition converts to Groovy correctly`() {
        val lspPos = CoordinateSystem.LspPosition(3, 7)
        val groovyPos = lspPos.toGroovy()
        assertEquals(4, groovyPos.line)
        assertEquals(8, groovyPos.column)
    }

    @Test
    fun `GroovyPosition converts to LSP correctly`() {
        val groovyPos = CoordinateSystem.GroovyPosition(4, 8)
        val lspPos = groovyPos.toLsp()
        assertEquals(3, lspPos.line)
        assertEquals(7, lspPos.character)
    }

    @Test
    fun `Position object conversion works`() {
        val lsp4jPos = Position(2, 5)
        val groovyPos = CoordinateSystem.lspToGroovy(lsp4jPos)
        assertEquals(3, groovyPos.line)
        assertEquals(6, groovyPos.column)
    }

    @Test
    fun `isValidNodePosition works with valid nodes`() = runTest {
        val groovyCode = "class TestClass {}"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        // Valid node should have valid positions
        assertTrue(CoordinateSystem.isValidNodePosition(classNode))
    }

    @Test
    fun `getNodeLspRange returns correct range for valid node`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {}
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        val range = CoordinateSystem.getNodeLspRange(classNode)
        assertNotNull(range)

        // Verify the range contains the class definition
        assertTrue(range.start.line >= 0)
        assertTrue(range.start.character >= 0)
        assertTrue(range.end.line >= range.start.line)
    }

    @Test
    fun `getNodeLspRange works with valid nodes`() = runTest {
        val groovyCode = "class TestClass {}"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        val range = CoordinateSystem.getNodeLspRange(classNode)
        assertNotNull(range)
    }

    @Test
    fun `nodeContainsPosition works with LSP coordinates`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        // Test with LSP coordinates (0-based)
        assertTrue(CoordinateSystem.nodeContainsPosition(classNode, 0, 5))
        assertTrue(CoordinateSystem.nodeContainsPosition(classNode, 1, 4))

        // Test with Position object
        val lspPosition = Position(0, 5)
        assertTrue(CoordinateSystem.nodeContainsPosition(classNode, lspPosition))
    }

    @Test
    fun `nodeContainsPosition correctly handles single-line nodes`() = runTest {
        val groovyCode = "def x = 42"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find a field or variable declaration
        val scriptClass = ast.scriptClassDummy
        if (scriptClass.fields.isNotEmpty()) {
            val fieldNode = scriptClass.fields.first()

            // Test position within the field declaration
            val range = CoordinateSystem.getNodeLspRange(fieldNode)
            assertNotNull(range)

            // Test position within range
            assertTrue(CoordinateSystem.nodeContainsPosition(fieldNode, range.start.line, range.start.character))

            // Test position outside range
            assertFalse(CoordinateSystem.nodeContainsPosition(fieldNode, range.end.line + 1, 0))
        }
    }

    @Test
    fun `nodeContainsPosition correctly handles multi-line nodes`() = runTest {
        val groovyCode = """
            class MultilineClass {
                def method() {
                    return "test"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        // Test position on first line
        assertTrue(CoordinateSystem.nodeContainsPosition(classNode, 0, 6)) // Within "class"

        // Test position on middle line
        assertTrue(CoordinateSystem.nodeContainsPosition(classNode, 1, 8)) // Within method

        // Test position on last line (if we can determine it)
        val range = CoordinateSystem.getNodeLspRange(classNode)
        assertNotNull(range)
        assertFalse(CoordinateSystem.nodeContainsPosition(classNode, range.end.line, range.end.character))
    }

    @Test
    fun `nodeContainsPositionRelaxed uses token length when end columns are missing`() {
        val node = VariableExpression("Example")
        node.lineNumber = 2
        node.columnNumber = 4
        node.lastLineNumber = 0
        node.lastColumnNumber = 0

        // LSP coordinates are 0-based (line 1, character 3 is Groovy 2:4).
        val inside = CoordinateSystem.nodeContainsPositionRelaxed(node, 1, 5) { node.name.length }
        val outside = CoordinateSystem.nodeContainsPositionRelaxed(node, 1, 20) { node.name.length }

        assertTrue(inside)
        assertFalse(outside)
    }

    @Test
    fun `nodeContainsPositionRelaxed returns false for invalid node positions`() {
        val node = VariableExpression("Bad")
        node.lineNumber = 0
        node.columnNumber = 0
        node.lastLineNumber = 0
        node.lastColumnNumber = 0

        val result = CoordinateSystem.nodeContainsPositionRelaxed(node, 0, 0) { node.name.length }

        assertFalse(result)
    }

    @Test
    fun `type-safe position wrappers work correctly`() {
        val lspPos = CoordinateSystem.LspPosition(5, 10)
        val groovyPos = lspPos.toGroovy()
        val convertedBack = groovyPos.toLsp()

        assertEquals(lspPos.line, convertedBack.line)
        assertEquals(lspPos.character, convertedBack.character)
    }

    @Test
    fun `range conversions work correctly`() {
        val lspStart = CoordinateSystem.LspPosition(1, 2)
        val lspEnd = CoordinateSystem.LspPosition(3, 4)
        val lspRange = CoordinateSystem.LspRange(lspStart, lspEnd)

        val groovyStart = CoordinateSystem.GroovyPosition(2, 3)
        val groovyEnd = CoordinateSystem.GroovyPosition(4, 5)
        val groovyRange = CoordinateSystem.GroovyRange(groovyStart, groovyEnd)

        val convertedLspRange = groovyRange.toLsp()
        assertEquals(lspRange.start.line, convertedLspRange.start.line)
        assertEquals(lspRange.start.character, convertedLspRange.start.character)
        assertEquals(lspRange.end.line, convertedLspRange.end.line)
        assertEquals(lspRange.end.character, convertedLspRange.end.character)
    }

    @Test
    fun `getNodeLspRange returns exclusive end column for LSP spec compliance`() = runTest {
        // Groovy AST uses 1-based INCLUSIVE columns for both start and end
        // LSP uses 0-based, start INCLUSIVE, end EXCLUSIVE
        // This test verifies the fix for incorrect end column conversion
        //
        // For a node at Groovy columns 5-10 (inclusive):
        // - Start: 5 -> 4 (subtract 1 for 0-based)
        // - End: 10 -> 10 (NO subtraction - 1-based inclusive = 0-based exclusive)
        val groovyCode = "def x = 42"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find a node with valid position info
        val scriptClass = ast.scriptClassDummy
        if (scriptClass.fields.isNotEmpty()) {
            val fieldNode = scriptClass.fields.first()
            val range = CoordinateSystem.getNodeLspRange(fieldNode)
            assertNotNull(range)

            // The end character should equal lastColumnNumber (no -1)
            // because LSP end is exclusive and Groovy end is 1-based inclusive
            // Example: Groovy lastColumnNumber=10 (1-based, inclusive, meaning char at col 10 is last)
            // LSP end.character=10 (0-based, exclusive, meaning "up to but not including pos 10")
            // These represent the same boundary!

            // Verify end character equals the Groovy lastColumnNumber (not lastColumnNumber - 1)
            assertEquals(
                fieldNode.lastColumnNumber,
                range.end.character,
                "LSP end character should equal Groovy lastColumnNumber (exclusive boundary)",
            )
        }
    }

    /**
     * Feedback from PR #741: Exclusive end column breaks last-character position containment.
     * Groovy's lastColumnNumber is inclusive, so we must use `<=` comparison.
     */
    @Test
    fun `nodeContainsPosition should include end column because Groovy is inclusive`() = runTest {
        val groovyCode = "class Test { int x }"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find a node with valid position info
        val classNode = ast.classes.first()
        val fieldNode = classNode.fields.first()
        val range = CoordinateSystem.getNodeLspRange(fieldNode)
        assertNotNull(range)

        // The issue reported is that nodeContainsPosition uses exclusive end column '<'
        // But Groovy lastColumnNumber is inclusive, so it should be '<='
        // Or more precisely, since we convert to Groovy coordinates, we should match inclusive.

        // Let's test the boundary condition:
        // If checking containment using Groovy coordinates directly via helper,
        // we expect the last column to be INCLUDED.

        // Let's use the LSP coordinate that corresponds to the last character.
        // LSP range is [start, end), where end is exclusive.
        // So end.character - 1 should be the last character inside the node.
        val lastCharCol = range.end.character - 1
        val lastCharLine = range.end.line

        // This should be true
        assertTrue(
            CoordinateSystem.nodeContainsPosition(fieldNode, lastCharLine, lastCharCol),
            "Position at current node end char should be contained",
        )
    }

    @Test
    fun `nodeContainsPosition should return false for synthetic or invalid nodes`() = runTest {
        val node = VariableExpression("synthetic")
        // Synthetic nodes often have -1 or 0 for positions
        node.lineNumber = 0
        node.columnNumber = 0
        node.lastLineNumber = 0
        node.lastColumnNumber = 0

        assertFalse(CoordinateSystem.nodeContainsPosition(node, 0, 0), "Should not contain position for 0,0 node")
        assertFalse(CoordinateSystem.isValidNodePosition(node), "Node with 0,0 should be invalid")

        node.lineNumber = -1
        assertFalse(CoordinateSystem.isValidNodePosition(node), "Node with -1 line should be invalid")
    }

    @Test
    fun `coordinate conversion should handle negative inputs gracefully`() {
        // While we don't expect negative inputs, the system should be robust
        val groovyPos = CoordinateSystem.lspToGroovy(-1, -1)
        assertEquals(0, groovyPos.line)
        assertEquals(0, groovyPos.column)

        val lspPos = CoordinateSystem.groovyToLsp(0, 0)
        assertEquals(-1, lspPos.line)
        assertEquals(-1, lspPos.character)
    }

    @Test
    fun `nodeContainsPosition should handle single-character nodes`() = runTest {
        val node = VariableExpression("a")
        node.lineNumber = 10
        node.columnNumber = 5
        node.lastLineNumber = 10
        node.lastColumnNumber = 5 // Single character

        assertTrue(CoordinateSystem.nodeContainsPosition(node, 9, 4), "Should contain its only character")
        assertFalse(CoordinateSystem.nodeContainsPosition(node, 9, 3), "Should not contain character before")
        assertFalse(CoordinateSystem.nodeContainsPosition(node, 9, 5), "Should not contain character after")
    }

    @Test
    fun `nodeContainsPosition should handle multi-line boundary conditions`() = runTest {
        val node = VariableExpression("multi")
        node.lineNumber = 10
        node.columnNumber = 5
        node.lastLineNumber = 12
        node.lastColumnNumber = 10

        // Middle line boundary conditions
        assertTrue(
            CoordinateSystem.nodeContainsPosition(node, 10, 0),
            "Line 11 (LSP 10) starts at beginning of line because it's a middle line",
        )
        assertTrue(
            CoordinateSystem.nodeContainsPosition(node, 10, 100),
            "Line 11 (LSP 10) ends at end of line because it's a middle line",
        )

        // Start line boundary
        assertFalse(CoordinateSystem.nodeContainsPosition(node, 9, 3), "Line 10 (LSP 9) starts at column 5 (LSP 4)")
        assertTrue(CoordinateSystem.nodeContainsPosition(node, 9, 4), "Line 10 (LSP 9) contains column 5 (LSP 4)")

        // End line boundary
        assertTrue(CoordinateSystem.nodeContainsPosition(node, 11, 9), "Line 12 (LSP 11) ends at column 10 (LSP 9)")
        assertFalse(
            CoordinateSystem.nodeContainsPosition(node, 11, 10),
            "Line 12 (LSP 11) does not contain column 11 (LSP 10)",
        )
    }
}
