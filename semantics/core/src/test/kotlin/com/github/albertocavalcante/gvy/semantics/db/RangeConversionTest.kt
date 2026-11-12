// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ranges are LSP ranges: 0-indexed and half-open. Member occurrences cover their identifier node;
 * declaration symbols retain the whole declaration range.
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
    @DisplayName("a method call occurrence covers exactly the method identifier")
    fun methodCall() {
        val call = doc.calls("bar").first { it.range.startLine == 5 }
        assertEquals(Range(5, 4, 5, 7), call.range)
        assertEquals("bar", call.range.text())
    }

    @Test
    @DisplayName("a multi-line call occurrence excludes the receiver and arguments")
    fun multiLineCall() {
        val call = doc.calls("bar").first { it.range.startLine == 7 }
        assertEquals(Range(7, 4, 7, 7), call.range)
        assertEquals("bar", call.range.text())
    }

    @Test
    @DisplayName("a property occurrence covers exactly the property identifier")
    fun propertyAccess() {
        val access = doc.occurrences.single { it.range.startLine == 6 && it.symbol.contains("name") }
        assertEquals(Range(6, 4, 6, 8), access.range)
        assertEquals("name", access.range.text())
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
