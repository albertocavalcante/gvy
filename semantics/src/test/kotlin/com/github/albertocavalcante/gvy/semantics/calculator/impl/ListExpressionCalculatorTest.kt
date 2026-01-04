package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListExpressionCalculatorTest {

    // Test doubles
    data class MockListExpression(val expressions: List<Any>)

    val intType = SemanticType.Known("java.lang.Integer", emptyList())
    val stringType = SemanticType.Known("java.lang.String", emptyList())

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
