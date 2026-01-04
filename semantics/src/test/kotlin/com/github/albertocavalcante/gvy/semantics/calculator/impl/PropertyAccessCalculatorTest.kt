package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PropertyAccessCalculatorTest {

    // Test doubles
    data class MockPropertyExpression(val objectExpression: Any, val property: Any)
    data class MockConstantExpression(val value: Any)

    val stringType = SemanticType.Known("java.lang.String", emptyList())
    val intType = SemanticType.Known("java.lang.Integer", emptyList())

    @Test
    fun `should calculate property type from context`() {
        val calculator = PropertyAccessCalculator()
        val receiver = "receiverNode"
        val property = MockConstantExpression("length")
        val node = MockPropertyExpression(receiver, property)

        val context = object : MockContext() {
            override fun calculateType(node: Any) = if (node == receiver) stringType else SemanticType.Unknown("Mock")

            override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? {
                if (receiverType == stringType && fieldName == "length") {
                    return intType
                }
                return null
            }
        }

        val result = calculator.calculate(node, context)
        assertEquals(intType, result)
    }

    open class MockContext : TypeContext {
        override fun resolveType(fqn: String): SemanticType = SemanticType.Unknown("Mock")
        override fun calculateType(node: Any): SemanticType = SemanticType.Unknown("Mock")
        override fun lookupSymbol(name: String): SemanticType? = null
        override fun getMethodReturnType(
            receiverType: SemanticType,
            methodName: String,
            argumentTypes: List<SemanticType>,
        ): SemanticType? = null

        override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? = null
        override val isStaticCompilation = false
    }
}
