package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.TypeLub
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

class ListExpressionCalculatorTest {

    // Test doubles
    data class MockListExpression(val expressions: List<Any>)

    data class NotAListExpression(val other: Any?)

    val intType = SemanticType.Known("java.lang.Integer", emptyList())
    val stringType = SemanticType.Known("java.lang.String", emptyList())

    @TestFactory
    fun `list generic arguments represent boxed values rather than primitives`(): List<DynamicTest> = listOf(
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
            val node = MockListExpression(listOf("value"))

            val result = ListExpressionCalculator().calculateResult(node, mockContext(mapOf("value" to primitive)))

            assertEquals(
                SemanticType.Known("java.util.ArrayList", listOf(SemanticType.Known(expected))),
                result.getOrNull(),
            )
        }
    }

    @Test
    fun `list primitive array elements remain arrays rather than boxed component arrays`() {
        val array = SemanticType.Array(TypeConstants.INT)
        val node = MockListExpression(listOf("array1", "array2"))
        val context = mockContext(mapOf("array1" to array, "array2" to array))

        val result = ListExpressionCalculator().calculateResult(node, context)

        assertEquals(SemanticType.Known("java.util.ArrayList", listOf(array)), result.getOrNull())
    }

    @Test
    fun `null-only list uses a legal Object type argument`() {
        val node = MockListExpression(listOf("first", "second"))
        val context = mockContext(mapOf("first" to SemanticType.Null, "second" to SemanticType.Null))

        val result = ListExpressionCalculator().calculateResult(node, context)

        assertEquals(SemanticType.Known("java.util.ArrayList", listOf(TypeConstants.OBJECT)), result.getOrNull())
    }

    @Test
    fun `nullable primitive list elements retain the boxed value type`() {
        val node = MockListExpression(listOf("nil", "value"))
        val context = mockContext(mapOf("nil" to SemanticType.Null, "value" to TypeConstants.INT))

        val result = ListExpressionCalculator().calculateResult(node, context)

        assertEquals(SemanticType.Known("java.util.ArrayList", listOf(intType)), result.getOrNull())
    }

    @Test
    fun `mixed integer and long elements use Number without changing arithmetic promotion`() {
        assertEquals(TypeConstants.LONG, TypeLub.lub(TypeConstants.INT, TypeConstants.LONG))
        val node = MockListExpression(listOf("integer", "long"))
        val context = mockContext(mapOf("integer" to TypeConstants.INT, "long" to TypeConstants.LONG))

        val result = ListExpressionCalculator().calculateResult(node, context)

        assertEquals(SemanticType.Known("java.util.ArrayList", listOf(TypeConstants.NUMBER)), result.getOrNull())
    }

    @Test
    fun `already boxed mixed numeric elements also use a common reference ancestor`() {
        val node = MockListExpression(listOf("integer", "long"))
        val context = mockContext(mapOf("integer" to intType, "long" to SemanticType.Known("java.lang.Long")))

        val result = ListExpressionCalculator().calculateResult(node, context)

        assertEquals(SemanticType.Known("java.util.ArrayList", listOf(TypeConstants.NUMBER)), result.getOrNull())
    }

    @Test
    fun `dynamic list elements use Object instead of an invalid def generic argument`() {
        val node = MockListExpression(listOf("value"))

        val result = ListExpressionCalculator().calculateResult(
            node,
            mockContext(mapOf("value" to SemanticType.Dynamic())),
        )

        assertEquals(SemanticType.Known("java.util.ArrayList", listOf(TypeConstants.OBJECT)), result.getOrNull())
    }

    @Test
    fun `should calculate List of Integers`() {
        val calculator = ListExpressionCalculator()
        val node = MockListExpression(listOf("1", "2"))
        val context = mockContext(mapOf("1" to intType, "2" to intType))

        val result = calculator.calculate(node, context)

        assertTrue(result is SemanticType.Known)
        val known = result as SemanticType.Known
        assertEquals("java.util.ArrayList", known.fqn)
        assertEquals(1, known.typeArgs.size)
        // LUB of Integer, Integer -> Integer
        assertEquals("java.lang.Integer", (known.typeArgs[0] as SemanticType.Known).fqn)
    }

    @Test
    fun `should calculate List of mixed types (String, Int)`() {
        // LUB(String, Integer) -> Comparable
        // TypeLub logic: if no common numeric/string rule, fallback to common ancestor.
        // String and Integer both implement Comparable and Serializable; this asserts the interface
        // priority ranking selects Comparable over Serializable.

        val calculator = ListExpressionCalculator()
        val node = MockListExpression(listOf("str", "int"))
        val context = mockContext(mapOf("str" to stringType, "int" to intType))

        val result = calculator.calculate(node, context)

        assertTrue(result is SemanticType.Known)
        val known = result as SemanticType.Known
        assertEquals("java.util.ArrayList", known.fqn)

        val elemType = known.typeArgs[0] as SemanticType.Known
        assertEquals("java.lang.Comparable", elemType.fqn)
    }

    @Test
    fun `should calculate Empty List as List of Object`() {
        val calculator = ListExpressionCalculator()
        val node = MockListExpression(emptyList())
        val context = mockContext(emptyMap())

        val result = calculator.calculate(node, context)

        assertTrue(result is SemanticType.Known)
        val known = result as SemanticType.Known
        assertEquals("java.util.ArrayList", known.fqn)
        // Empty list should be typed as List<Object>
        assertEquals(1, known.typeArgs.size)
        assertEquals("java.lang.Object", (known.typeArgs[0] as SemanticType.Known).fqn)
    }

    @Test
    fun `should return null for non-list nodes`() {
        val calculator = ListExpressionCalculator()
        val node = NotAListExpression("x")

        val result = calculator.calculate(node, testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult should return Right with ArrayList of Integers`() {
        val calculator = ListExpressionCalculator()
        val node = MockListExpression(listOf("1", "2"))
        val context = mockContext(mapOf("1" to intType, "2" to intType))

        val result = calculator.calculateResult(node, context)

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type ->
                assertTrue(type is SemanticType.Known)
                val known = type as SemanticType.Known
                assertEquals("java.util.ArrayList", known.fqn)
                assertEquals(1, known.typeArgs.size)
                assertEquals("java.lang.Integer", (known.typeArgs[0] as SemanticType.Known).fqn)
            },
        )
    }

    @Test
    fun `calculateResult should return Right with ArrayList of Object for empty list`() {
        val calculator = ListExpressionCalculator()
        val node = MockListExpression(emptyList())
        val context = mockContext(emptyMap())

        val result = calculator.calculateResult(node, context)

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type ->
                assertTrue(type is SemanticType.Known)
                val known = type as SemanticType.Known
                assertEquals("java.util.ArrayList", known.fqn)
                assertEquals(1, known.typeArgs.size)
                assertEquals("java.lang.Object", (known.typeArgs[0] as SemanticType.Known).fqn)
            },
        )
    }

    @Test
    fun `calculateResult should return Left with UnsupportedNode for non-list nodes`() {
        val calculator = ListExpressionCalculator()
        val node = NotAListExpression("x")

        val result = calculator.calculateResult(node, testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(
                    error.reason.contains("NotAListExpression") || error.reason.contains("no expressions property"),
                )
            },
            ifRight = { fail("Expected Left but got Right($it)") },
        )
    }

    @Test
    fun `calculateResult should propagate error from failing child element`() {
        val calculator = ListExpressionCalculator()
        val failingContext = testContext(failOnCalculateType = true)

        // Create a list with at least one element
        val node = MockListExpression(listOf(object {}))

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

        override fun calculateType(node: Any): SemanticType = types[node] ?: SemanticType.Unknown("Mock")

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
