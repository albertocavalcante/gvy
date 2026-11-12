// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.testContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class MapExpressionCalculatorTest {

    // Test doubles
    data class MockMapEntry(val keyExpression: Any, val valueExpression: Any)
    data class MockMapExpression(val mapEntryExpressions: List<Any>)

    data class NotAMapExpression(val other: Any?)

    val stringType = SemanticType.Known("java.lang.String", emptyList())
    val intType = SemanticType.Known("java.lang.Integer", emptyList())

    @TestFactory
    fun `map keys and values use boxed reference type arguments`(): List<DynamicTest> = listOf(
        PrimitiveKind.BOOLEAN to "java.lang.Boolean",
        PrimitiveKind.BYTE to "java.lang.Byte",
        PrimitiveKind.CHAR to "java.lang.Character",
        PrimitiveKind.SHORT to "java.lang.Short",
        PrimitiveKind.INT to "java.lang.Integer",
        PrimitiveKind.LONG to "java.lang.Long",
        PrimitiveKind.FLOAT to "java.lang.Float",
        PrimitiveKind.DOUBLE to "java.lang.Double",
        PrimitiveKind.VOID to "java.lang.Object",
    ).map { (kind, expected) ->
        dynamicTest("$kind -> $expected") {
            val primitive = SemanticType.Primitive(kind)
            val node = MockMapExpression(listOf(MockMapEntry("key", "value")))
            val context = mockContext(mapOf("key" to primitive, "value" to primitive))

            val result = MapExpressionCalculator().calculateResult(node, context)

            val boxed = SemanticType.Known(expected)
            assertEquals(SemanticType.Known("java.util.LinkedHashMap", listOf(boxed, boxed)), result.getOrNull())
        }
    }

    @Test
    fun `map primitive array keys and values remain primitive arrays`() {
        val array = SemanticType.Array(TypeConstants.INT)
        val node = MockMapExpression(listOf(MockMapEntry("key", "value")))

        val result = MapExpressionCalculator().calculateResult(
            node,
            mockContext(mapOf("key" to array, "value" to array)),
        )

        assertEquals(SemanticType.Known("java.util.LinkedHashMap", listOf(array, array)), result.getOrNull())
    }

    @Test
    fun `null-only map keys and values use legal Object arguments`() {
        val node = MockMapExpression(listOf(MockMapEntry("key", "value")))
        val context = mockContext(mapOf("key" to SemanticType.Null, "value" to SemanticType.Null))

        val result = MapExpressionCalculator().calculateResult(node, context)

        assertEquals(
            SemanticType.Known("java.util.LinkedHashMap", listOf(TypeConstants.OBJECT, TypeConstants.OBJECT)),
            result.getOrNull(),
        )
    }

    @Test
    fun `mixed integer and long map entries use Number for both axes without coercing values`() {
        val node = MockMapExpression(listOf(MockMapEntry("key1", "value1"), MockMapEntry("key2", "value2")))
        val context = mockContext(
            mapOf(
                "key1" to TypeConstants.INT,
                "value1" to TypeConstants.INT,
                "key2" to TypeConstants.LONG,
                "value2" to TypeConstants.LONG,
            ),
        )

        val result = MapExpressionCalculator().calculateResult(node, context)

        assertEquals(
            SemanticType.Known("java.util.LinkedHashMap", listOf(TypeConstants.NUMBER, TypeConstants.NUMBER)),
            result.getOrNull(),
        )
    }

    @Test
    fun `already boxed mixed numeric map values use Number`() {
        val node = MockMapExpression(listOf(MockMapEntry("key1", "value1"), MockMapEntry("key2", "value2")))
        val context = mockContext(
            mapOf(
                "key1" to stringType,
                "key2" to stringType,
                "value1" to intType,
                "value2" to SemanticType.Known("java.lang.Long"),
            ),
        )

        val result = MapExpressionCalculator().calculateResult(node, context)

        assertEquals(
            SemanticType.Known("java.util.LinkedHashMap", listOf(stringType, TypeConstants.NUMBER)),
            result.getOrNull(),
        )
    }

    @Test
    fun `should calculate Map of String to Integer`() {
        val calculator = MapExpressionCalculator()
        val entry1 = MockMapEntry("key1", "val1")
        val entry2 = MockMapEntry("key2", "val2")
        val node = MockMapExpression(listOf(entry1, entry2))

        val context = mockContext(
            mapOf(
                "key1" to stringType,
                "key2" to stringType,
                "val1" to intType,
                "val2" to intType,
            ),
        )

        val result = calculator.calculate(node, context)

        assertTrue(result is SemanticType.Known)
        val known = result as SemanticType.Known
        assertEquals("java.util.LinkedHashMap", known.fqn)
        assertEquals(2, known.typeArgs.size)
        // K = String
        assertEquals("java.lang.String", (known.typeArgs[0] as SemanticType.Known).fqn)
        // V = Integer
        assertEquals("java.lang.Integer", (known.typeArgs[1] as SemanticType.Known).fqn)
    }

    @Test
    fun `should calculate Empty Map`() {
        val calculator = MapExpressionCalculator()
        val node = MockMapExpression(emptyList())
        val context = mockContext(emptyMap())

        val result = calculator.calculate(node, context)

        assertTrue(result is SemanticType.Known)
        val known = result as SemanticType.Known
        assertEquals("java.util.LinkedHashMap", known.fqn)
        assertEquals(2, known.typeArgs.size)
        // Defaults to Object, Object
        assertEquals("java.lang.Object", (known.typeArgs[0] as SemanticType.Known).fqn)
        assertEquals("java.lang.Object", (known.typeArgs[1] as SemanticType.Known).fqn)
    }

    @Test
    fun `should return null for non-map nodes`() {
        val calculator = MapExpressionCalculator()
        val node = NotAMapExpression("x")

        val result = calculator.calculate(node, testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult should return Right with LinkedHashMap of String to Integer`() {
        val calculator = MapExpressionCalculator()
        val entry1 = MockMapEntry("key1", "val1")
        val entry2 = MockMapEntry("key2", "val2")
        val node = MockMapExpression(listOf(entry1, entry2))

        val context = mockContext(
            mapOf(
                "key1" to stringType,
                "key2" to stringType,
                "val1" to intType,
                "val2" to intType,
            ),
        )

        val result = calculator.calculateResult(node, context)

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type ->
                assertTrue(type is SemanticType.Known)
                val known = type as SemanticType.Known
                assertEquals("java.util.LinkedHashMap", known.fqn)
                assertEquals(2, known.typeArgs.size)
                assertEquals("java.lang.String", (known.typeArgs[0] as SemanticType.Known).fqn)
                assertEquals("java.lang.Integer", (known.typeArgs[1] as SemanticType.Known).fqn)
            },
        )
    }

    @Test
    fun `calculateResult should return Right with LinkedHashMap of Object to Object for empty map`() {
        val calculator = MapExpressionCalculator()
        val node = MockMapExpression(emptyList())
        val context = mockContext(emptyMap())

        val result = calculator.calculateResult(node, context)

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type ->
                assertTrue(type is SemanticType.Known)
                val known = type as SemanticType.Known
                assertEquals("java.util.LinkedHashMap", known.fqn)
                assertEquals(2, known.typeArgs.size)
                assertEquals("java.lang.Object", (known.typeArgs[0] as SemanticType.Known).fqn)
                assertEquals("java.lang.Object", (known.typeArgs[1] as SemanticType.Known).fqn)
            },
        )
    }

    @Test
    fun `calculateResult should return Left with UnsupportedNode for non-map nodes`() {
        val calculator = MapExpressionCalculator()
        val node = NotAMapExpression("x")

        val result = calculator.calculateResult(node, testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(
                    error.reason.contains("NotAMapExpression") ||
                        error.reason.contains("no mapEntryExpressions property"),
                )
            },
            ifRight = { fail("Expected Left but got Right($it)") },
        )
    }

    @Test
    fun `calculateResult should propagate error from failing child key expression`() {
        val calculator = MapExpressionCalculator()
        val failingContext = testContext(failOnCalculateType = true)

        // Create a map with at least one entry
        val entry = MockMapEntry(object {}, object {})
        val node = MockMapExpression(listOf(entry))

        val result = calculator.calculateResult(node, failingContext)

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.SymbolNotFound)
            },
            ifRight = { fail("Expected Left (error propagation) but got Right($it)") },
        )
    }

    private fun mockContext(types: Map<Any, SemanticType>) = object : TypeContext {
        override fun resolveType(fqn: String) = SemanticType.Unknown("Mock")
        override fun calculateType(node: Any) = types[node] ?: SemanticType.Unknown("Mock")
        override fun lookupSymbol(name: String) = null
        override fun getMethodReturnType(
            receiverType: SemanticType,
            methodName: String,
            argumentTypes: List<SemanticType>,
        ) = null

        override fun getFieldType(receiverType: SemanticType, fieldName: String) = null
        override val isStaticCompilation = false
    }
}
