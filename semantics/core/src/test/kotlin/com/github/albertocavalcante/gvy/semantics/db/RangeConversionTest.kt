package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ranges are LSP ranges: 0-indexed and half-open, so the text they cover is exactly the node's text.
 * Groovy's `lastColumnNumber` is 1-based and one past the last character; the end column is that minus 1.
 */
class RangeConversionTest : SymbolIdTestSupport() {

    private val source = """
        class Foo {
            String name
            int bar(int a, int b) { a }
        }
        def foo = new Foo()
        foo.bar(1, 2)
        foo.name
        foo.bar(
            1,
            2
        )
    """.trimIndent()

    private val lines = source.lines()

    /** The text a half-open range covers. */
    private fun Range.text(): String = if (startLine == endLine) {
        lines[startLine].substring(startColumn, endColumn)
    } else {
        buildList {
            add(lines[startLine].substring(startColumn))
            for (l in startLine + 1 until endLine) add(lines[l])
            add(lines[endLine].substring(0, endColumn))
        }.joinToString("\n")
    }

    private val doc = build(source)

    @Test
    @DisplayName("a method call covers exactly the call text")
    fun methodCall() {
        val call = doc.calls("bar").first { it.range.startLine == 5 }
        assertEquals(Range(5, 0, 5, 13), call.range)
        assertEquals("foo.bar(1, 2)", call.range.text())
    }

    @Test
    @DisplayName("a multi-line call ends right after its closing parenthesis")
    fun multiLineCall() {
        val call = doc.calls("bar").first { it.range.startLine == 7 }
        assertEquals(Range(7, 0, 10, 1), call.range)
        assertEquals("foo.bar(\n    1,\n    2\n)", call.range.text())
    }

    @Test
    @DisplayName("a property access covers exactly the access text")
    fun propertyAccess() {
        val access = doc.occurrences.single { it.range.startLine == 6 && it.symbol.contains("name") }
        assertEquals(Range(6, 0, 6, 8), access.range)
        assertEquals("foo.name", access.range.text())
    }

    @Test
    @DisplayName("a variable reference covers exactly the variable name")
    fun variableReference() {
        val line = lines[2]
        val column = line.lastIndexOf('a')
        val reference = doc.occurrences.single {
            it.role == OccurrenceRole.REFERENCE && it.range.startLine == 2
        }
        assertEquals(Range(2, column, 2, column + 1), reference.range)
        assertEquals("a", reference.range.text())
    }

    @Test
    @DisplayName("a class definition covers the whole declaration, ending after its closing brace")
    fun classDeclaration() {
        val definition = doc.symbols.single { it.kind == SymbolKind.CLASS && it.name == "Foo" }
        assertEquals(Range(0, 0, 3, 1), definition.range)
        assertTrue(definition.range.text().endsWith("}"))
    }

    @Test
    @DisplayName("Groovy 1-based positions map to half-open LSP ranges; unset or empty ones to null")
    fun conversion() {
        assertEquals(Range(0, 0, 0, 1), SemanticDocumentBuilder.groovyToLspRange(1, 1, 1, 2))
        assertEquals(Range(4, 2, 6, 3), SemanticDocumentBuilder.groovyToLspRange(5, 3, 7, 4))
        assertNull(SemanticDocumentBuilder.groovyToLspRange(1, 1, 1, 1))
        assertNull(SemanticDocumentBuilder.groovyToLspRange(-1, -1, -1, -1))
        assertNull(SemanticDocumentBuilder.groovyToLspRange(2, 1, 1, 5))
    }
}
