// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImplementationBindingsTest {
    private val variable = ImplementationTypeReference("T", isVariable = true)
    private val string = ImplementationTypeReference("java.lang.String")
    private val integer = ImplementationTypeReference("java.lang.Integer")
    private val method = ImplementationMethod(
        "I#convert(T).",
        "convert",
        listOf("java.lang.Object"),
        null,
        true,
        false,
        false,
        parameterReferences = listOf(variable),
    )

    @Test
    fun `independent viewpoints never share specialized parameters`() {
        val hierarchy = mapOf(
            "I#" to contract(),
            "Strings#" to child("I#", string),
            "Integers#" to child("I#", integer),
        )

        assertEquals(listOf(string), ImplementationBindings(hierarchy, "Strings#").parameters("I#", method))
        assertEquals(listOf(integer), ImplementationBindings(hierarchy, "Integers#").parameters("I#", method))
    }

    @Test
    fun `raw edges use the parent declared bound`() {
        val hierarchy = mapOf(
            "I#" to contract().copy(parameterDefaults = mapOf("T" to "java.lang.Number")),
            "Raw#" to child("I#", string).copy(parentArguments = emptyMap()),
        )

        assertEquals(
            listOf(ImplementationTypeReference("java.lang.Number")),
            ImplementationBindings(hierarchy, "Raw#").parameters("I#", method),
        )
    }

    @Test
    fun `agreeing diamond paths preserve their common specialization`() {
        val hierarchy = diamond(string)

        assertEquals(listOf(string), ImplementationBindings(hierarchy, "C#").parameters("I#", method))
    }

    @Test
    fun `conflicting diamond paths remain ambiguous regardless of traversal order`() {
        val hierarchy = diamond(integer)
        val reversed = hierarchy + ("C#" to hierarchy.getValue("C#").copy(interfaces = listOf("Right#", "Left#")))

        assertNull(ImplementationBindings(hierarchy, "C#").parameters("I#", method))
        assertNull(ImplementationBindings(reversed, "C#").parameters("I#", method))
    }

    @Test
    fun `growing array cycles terminate without retaining a guessed binding`() {
        val hierarchy = mapOf(
            "I#" to contract().copy(
                interfaces = listOf("I#"),
                parentArguments = mapOf("I#" to listOf(variable.copy(dimensions = 1))),
            ),
            "C#" to child("I#", string),
        )

        assertNull(ImplementationBindings(hierarchy, "C#").parameters("I#", method))
    }

    @Test
    fun `deep renamed bindings resolve with bounded stack space`() {
        val hierarchy = linkedMapOf("I#" to contract())
        repeat(10000) { index ->
            hierarchy["C$index#"] = contract().copy(
                isInterface = false,
                superclass = if (index == 0) "I#" else "C${index - 1}#",
                parentArguments = mapOf(
                    (if (index == 0) "I#" else "C${index - 1}#") to listOf(variable),
                ),
            )
        }
        hierarchy["Leaf#"] = child("C9999#", string)

        assertEquals(listOf(string), ImplementationBindings(hierarchy, "Leaf#").parameters("I#", method))
    }

    @Test
    fun `unknown owners and malformed argument arity cannot invent implementations`() {
        val hierarchy = mapOf(
            "I#" to contract(),
            "C#" to child("I#", string).copy(parentArguments = mapOf("I#" to listOf(string, integer))),
        )
        val bindings = ImplementationBindings(hierarchy, "C#")

        assertNull(bindings.parameters("I#", method))
        assertNull(bindings.parameters("Missing#", method))
    }

    @Test
    fun `array rank overflow cannot wrap into a matching signature`() {
        val hierarchy = mapOf("I#" to contract(), "C#" to child("I#", string.copy(dimensions = Int.MAX_VALUE)))

        assertNull(
            ImplementationBindings(hierarchy, "C#").parameters(
                "I#",
                method.copy(parameterReferences = listOf(variable.copy(dimensions = 1))),
            ),
        )
    }

    private fun contract(): ImplementationType = ImplementationType(
        true,
        true,
        null,
        emptyList(),
        listOf(method),
        parameterDefaults = mapOf("T" to "java.lang.Object"),
    )

    private fun child(parent: String, argument: ImplementationTypeReference): ImplementationType = ImplementationType(
        false,
        false,
        null,
        listOf(parent),
        emptyList(),
        parentArguments = mapOf(parent to listOf(argument)),
    )

    private fun diamond(rightArgument: ImplementationTypeReference): Map<String, ImplementationType> = mapOf(
        "I#" to contract(),
        "Left#" to child("I#", string).copy(isInterface = true),
        "Right#" to child("I#", rightArgument).copy(isInterface = true),
        "C#" to ImplementationType(false, false, null, listOf("Left#", "Right#"), emptyList()),
    )
}
