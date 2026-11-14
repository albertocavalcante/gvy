// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroovySourceDeclarationInspectorTest {
    private val inspector = GroovySourceDeclarationInspector()

    @Test
    fun `finds the unique method declaration in its exact owner`() {
        val source = """
            package sample
            class Other { String select(String value) { value } }
            class Example {
                // select() in a comment is not a declaration
                String decoy = "select()"
                @Deprecated
                /* 😀 */ public String select(String value) { value }
            }
        """.trimIndent()

        val definition = requireNotNull(
            inspector.inspectMember(source, "sample.Example", "select", URI.create("file:///Example.groovy")),
        )
        val methodLine = source.lines()[6]

        assertEquals(
            SourceSpan(6, methodLine.indexOf("select"), 6, methodLine.indexOf("select") + "select".length),
            definition.nameRange,
        )
        assertTrue(definition.declarationRange.contains(definition.nameRange))
    }

    @Test
    fun `declines overloaded methods instead of guessing from the name alone`() {
        val source = """
            package sample
            class Example {
                String select() { "select()" }
                String select(String value) { value }
            }
        """.trimIndent()

        assertNull(inspector.inspectMember(source, "sample.Example", "select", URI.create("file:///Example.groovy")))
    }

    @Test
    fun `declines a method when a same-name static field or property makes selection ambiguous`() {
        val source = """
            package sample
            class Example {
                static int select = 1
                static int select() { 2 }
            }
        """.trimIndent()

        assertNull(inspector.inspectMember(source, "sample.Example", "select", URI.create("file:///Example.groovy")))
    }

    @Test
    fun `declines same-name methods outside the selected owner`() {
        val source = """
            package sample
            class Other { String select() { "select()" } }
            class Example { String value() { "select()" } }
        """.trimIndent()

        assertNull(inspector.inspectMember(source, "sample.Example", "select", URI.create("file:///Example.groovy")))
    }
}
