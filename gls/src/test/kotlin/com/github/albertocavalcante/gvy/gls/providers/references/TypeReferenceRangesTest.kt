// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypeReferenceRangesTest {
    @Test
    fun `type heads exclude generic arguments class literals and comments`() {
        val cases = mapOf(
            "Alias<Foo>" to "Alias",
            "p.Foo<Alias>" to "Foo",
            "Alias.class" to "Alias",
            "p /* Alias < */ . Foo" to "Foo",
            "p // Alias <\n . Foo<Alias>" to "Foo",
            "p.Dollar\$Part<Alias>" to "Dollar\$Part",
            "p.𝒜lias<Foo>" to "𝒜lias",
        )
        cases.forEach { (source, expected) ->
            val index = TextIndex(source)
            val range = typeReferenceRange(
                index,
                Range(index.positionOf(0), index.positionOf(source.length)),
                setOf("Foo", "Alias", "Dollar\$Part", "𝒜lias"),
            )
            val start = source.indexOf(expected).takeIf { source == "Alias<Foo>" || source == "Alias.class" }
                ?: source.lastIndexOf(expected)
            assertEquals(Range(index.positionOf(start), index.positionOf(start + expected.length)), range, source)
        }
    }

    @Test
    fun `unknown or incomplete type heads cannot authorize alias references or rename`() {
        listOf("Alias<Foo>", "p.", "new Foo(Alias)", "'Foo'", "/* Foo */ Other").forEach { source ->
            val index = TextIndex(source)
            assertNull(
                typeReferenceRange(
                    index,
                    Range(index.positionOf(0), index.positionOf(source.length)),
                    setOf("Foo"),
                ),
                source,
            )
        }
    }
}
