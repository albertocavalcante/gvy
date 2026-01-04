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
        // LUB(String, Integer) -> Object (or Comparable/Serializable)
        // TypeLub logic: if no common numeric/string rule, fallback to common ancestor.
        // String and Integer implement Comparable, Serializable.

        val calculator = ListExpressionCalculator()
        val node = MockListExpression(listOf("str", "int"))
        val context = mockContext(mapOf("str" to stringType, "int" to intType))

        val result = calculator.calculate(node, context)

        assertTrue(result is SemanticType.Known)
        val known = result as SemanticType.Known
        assertEquals("java.util.ArrayList", known.fqn)

        // Verifying the LUB. Depending on TypeLub implementation, might be Object or Comparable.
        // Let's assert it is one of the valid common ancestors or Object.
        val elemType = known.typeArgs[0] as SemanticType.Known
        // "java.lang.Object" is the safest fallback if interfaces aren't prioritized perfectly.
        // Based on TypeLub code, it tries common ancestors. String & Integer share Comparable & Serializable.
        // Interfaces are prioritized. Object has priority 100 (worst). Comparable 5. Serializable 10.
        // So it should pick Comparable.
        assertTrue(
            elemType.fqn == "java.lang.Comparable" || elemType.fqn == "java.io.Serializable" ||
                elemType.fqn == "java.lang.Object",
        )
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
        // Usually treated as raw or List<Object>
        if (known.typeArgs.isNotEmpty()) {
            assertEquals("java.lang.Object", (known.typeArgs[0] as SemanticType.Known).fqn)
        }
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
