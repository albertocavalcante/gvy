package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.testContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class MapExpressionCalculatorTest {

    // Test doubles
    data class MockMapEntry(val keyExpression: Any, val valueExpression: Any)
    data class MockMapExpression(val mapEntryExpressions: List<Any>)

    data class NotAMapExpression(val other: Any?)

    val stringType = SemanticType.Known("java.lang.String", emptyList())
    val intType = SemanticType.Known("java.lang.Integer", emptyList())

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
