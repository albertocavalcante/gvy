package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreParserProviderTest {

    private val provider = CoreParserProvider()

    @Test
    fun `provider has correct name`() {
        assertEquals("core", provider.name)
    }

    @Test
    fun `provider has correct capabilities`() {
        assertTrue(provider.capabilities.supportsErrorRecovery)
        assertTrue(provider.capabilities.supportsCommentPreservation)
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
}
