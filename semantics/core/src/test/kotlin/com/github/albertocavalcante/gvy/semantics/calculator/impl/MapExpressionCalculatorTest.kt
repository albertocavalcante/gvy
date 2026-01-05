package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapExpressionCalculatorTest {

    // Test doubles
    data class MockMapEntry(val keyExpression: Any, val valueExpression: Any)
    data class MockMapExpression(val mapEntryExpressions: List<Any>)

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
