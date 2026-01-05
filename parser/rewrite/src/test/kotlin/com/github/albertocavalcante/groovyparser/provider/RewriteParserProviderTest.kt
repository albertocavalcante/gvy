package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewriteParserProviderTest {

    private val provider = RewriteParserProvider()

    @Test
    fun `provider has correct name`() {
        assertEquals("rewrite", provider.name)
    }

    @Test
    fun `provider has correct capabilities`() {
        assertFalse(provider.capabilities.supportsErrorRecovery)
        assertTrue(provider.capabilities.supportsCommentPreservation)
        assertFalse(provider.capabilities.supportsSymbolResolution)
        assertTrue(provider.capabilities.supportsRefactoring)
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
    fun `parse invalid code returns unsuccessful unit`() {
        val source = "class Broken { void method( { }"
        val unit = provider.parse(source)

        assertFalse(unit.isSuccessful)

        val diagnostics = unit.diagnostics()
        assertEquals(1, diagnostics.size)
        assertEquals(Severity.ERROR, diagnostics.single().severity)
        assertEquals("rewrite-parser", diagnostics.single().source)
        assertTrue(diagnostics.single().message.isNotBlank())
        assertTrue(unit.symbols().isEmpty())
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
    fun `parse script without class returns successful unit`() {
        val source = """
            def greeting = "Hello"
            println greeting
        """.trimIndent()
        val unit = provider.parse(source)

        assertTrue(unit.isSuccessful)
    }

    @Test
    fun `parse groovy closure returns successful unit`() {
        val source = """
            def list = [1, 2, 3]
            list.each { println it }
        """.trimIndent()
        val unit = provider.parse(source)

        assertTrue(unit.isSuccessful)
    }

    @Test
    fun `symbols extracts multiple classes`() {
        val source = """
            class Foo {}
            class Bar {}
        """.trimIndent()
        val unit = provider.parse(source)

        val symbols = unit.symbols()
        assertTrue(symbols.any { it.name == "Foo" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "Bar" && it.kind == SymbolKind.CLASS })
    }
}
