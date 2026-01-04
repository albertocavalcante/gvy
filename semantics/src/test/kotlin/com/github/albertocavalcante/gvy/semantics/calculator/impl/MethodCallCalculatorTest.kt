package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MethodCallCalculatorTest {

    // Test doubles
    data class MockMethodCall(val receiver: Any, val methodName: String, val arguments: List<Any>)

    val stringType = SemanticType.Known("java.lang.String", emptyList())
    val intType = SemanticType.Known("java.lang.Integer", emptyList())
    val booleanType = SemanticType.Known("java.lang.Boolean", emptyList())

    @Test
    fun `should calculate method return type from context`() {
        val calculator = MethodCallCalculator()
        val node = MockMethodCall("strInvoker", "length", emptyList())

        val context = object : MockContext() {
            override fun calculateType(node: Any) = stringType

            override fun getMethodReturnType(
                receiverType: SemanticType,
                methodName: String,
                argumentTypes: List<SemanticType>,
            ): SemanticType? {
                if (receiverType == stringType && methodName == "length" && argumentTypes.isEmpty()) {
                    return intType
                }
                return null
            }
        }

        val result = calculator.calculate(node, context)
        assertEquals(intType, result)
    }

    @Test
    fun `should handle arguments`() {
        val calculator = MethodCallCalculator()
        val node = MockMethodCall("strInvoker", "indexOf", listOf("arg1"))

        val context = object : MockContext() {
            override fun calculateType(node: Any): SemanticType {
                return if (node == "strInvoker") stringType else stringType // arg1 is string
            }

            override fun getMethodReturnType(
                receiverType: SemanticType,
                methodName: String,
                argumentTypes: List<SemanticType>,
            ): SemanticType? {
                val matches = receiverType == stringType &&
                    methodName == "indexOf" &&
                    argumentTypes.singleOrNull() == stringType

                if (matches) {
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
