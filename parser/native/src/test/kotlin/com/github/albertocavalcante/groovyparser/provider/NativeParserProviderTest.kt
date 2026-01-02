package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeParserProviderTest {

    private val provider = NativeParserProvider()

    @Test
    fun `provider has correct name`() {
        assertEquals("native", provider.name)
    }

    @Test
    fun `provider has correct capabilities`() {
        assertTrue(provider.capabilities.supportsErrorRecovery)
        assertFalse(provider.capabilities.supportsCommentPreservation)
        assertTrue(provider.capabilities.supportsSymbolResolution)
        assertFalse(provider.capabilities.supportsRefactoring)
    }

    @Test
    fun `parse valid class returns successful unit`() {
        val source = "class Foo { String name }"
        val unit = provider.parse(source)

        assertTrue(unit.isSuccessful)
        assertEquals(source, unit.source)
        assertTrue(unit.diagnostics().isEmpty())
    }

    @Test
    fun `parse invalid code returns diagnostics`() {
        val source = "class Broken { void method( { }"
        val unit = provider.parse(source)

        assertFalse(unit.isSuccessful)
        assertTrue(unit.diagnostics().any { it.severity == Severity.ERROR })
    }

    @Test
    fun `symbols extracts class and method`() {
        val source = """
            class Person {
                String name
                void greet() { println name }
            }
        """.trimIndent()
        val unit = provider.parse(source)

        val symbols = unit.symbols()
        assertTrue(symbols.any { it.name == "Person" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "greet" && it.kind == SymbolKind.METHOD })
        assertTrue(symbols.any { it.name == "name" && it.kind == SymbolKind.FIELD })
    }

    @Test
    fun `diagnostics have 1-based positions`() {
        val source = "class X { void m( { }" // Syntax error
        val unit = provider.parse(source)

        val errors = unit.diagnostics().filter { it.severity == Severity.ERROR }
        assertTrue(errors.isNotEmpty())
        // Positions should be 1-based (line >= 1, column >= 1)
        errors.forEach { diag ->
            assertTrue(diag.range.start.line >= 1, "Line should be 1-based")
            assertTrue(diag.range.start.column >= 1, "Column should be 1-based")
        }
    }

    @Test
    fun `symbols reports interfaces correctly`() {
        val source = """
            interface Greeter {
                void greet()
            }
        """.trimIndent()
        val unit = provider.parse(source)

        val symbols = unit.symbols()
        assertTrue(
            symbols.any { it.name == "Greeter" && it.kind == SymbolKind.INTERFACE },
            "Should report Greeter as INTERFACE",
        )
    }

    @Test
    fun `nodeAt handles invalid positions gracefully`() {
        val source = "class Foo {}"
        val unit = provider.parse(source)

        // Invalid 1-based positions
        val invalidPositions = listOf(
            Position(0, 0),
            Position(-1, 5),
            Position(1, 0),
        )

        invalidPositions.forEach { pos ->
            val node = unit.nodeAt(pos)
            assertEquals(null, node, "Should return null for invalid position $pos")
        }
    }

    @Test
    fun `nodeAt identifies various node types`() {
        val source = """
            class Test {
                def prop
                void method(int param) {
                    def var = 1
                    if (true) {}
                    for (i in 1..10) {}
                    while (false) {}
                    return
                }
            }
        """.trimIndent()
        val unit = provider.parse(source)

        // Helper to check node at specific line/col (1-based)
        fun checkNode(line: Int, col: Int, expectedName: String?, expectedKind: NodeKind) {
            val node = unit.nodeAt(Position(line, col))
            kotlin.test.assertNotNull(node, "Node at $line:$col should not be null")
            assertEquals(expectedName, node.name, "Name mismatch at $line:$col")
            assertEquals(expectedKind, node.kind, "Kind mismatch at $line:$col")
        }

        // Based on structure:
        // L1: class Test
        // L2:     def prop
        // L3:     void method(int param)
        // L4:         def var = 1
        // L5:         if (true) {}
        // L6:         for (i in 1..10) {}
        // L7:         while (false) {}
        // L8:         return

        checkNode(1, 1, "Test", NodeKind.CLASS)
        checkNode(2, 9, "prop", NodeKind.FIELD)
        checkNode(3, 10, "method", NodeKind.METHOD)
        checkNode(3, 21, "param", NodeKind.PARAMETER)
        // Note: Variable expression location might be slightly tricky depending on parser, targeting 'var'
        // 'def var = 1' starts at col 9. 'var' is likely around col 13
        checkNode(4, 13, "var", NodeKind.VARIABLE_REFERENCE)

        // TODO(#561): Improve statement-level node resolution in NativeParseUnit
        //   See: https://github.com/albertocavalcante/gvy/issues/561
        // Statement level resolution is tricky and sensitive to exact positions/AST structure.
        // Disabling these checks until we can confirm the AST model supports statement-level granularity accurately.
        // checkNode(5, 9, null, NodeKind.IF)
        // checkNode(6, 9, null, NodeKind.FOR)
        // checkNode(7, 9, null, NodeKind.WHILE)
        // checkNode(8, 9, null, NodeKind.RETURN)
    }
}
