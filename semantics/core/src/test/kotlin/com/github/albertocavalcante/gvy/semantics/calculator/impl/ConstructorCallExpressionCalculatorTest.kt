package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstructorCallExpressionCalculatorTest {
    private val calculator = ConstructorCallExpressionCalculator()
    private val context = mockContext()

    @Test
    fun `returns null for non-constructor nodes`() {
        val result = calculator.calculate("not a constructor", context)
        assertNull(result)
    }

    @Test
    fun `returns null for node without type property`() {
        val fakeConstructor = object {
            override fun toString() = "ConstructorCallExpression"
        }
        // This won't match because class name check uses simpleName
        val result = calculator.calculate(fakeConstructor, context)
        assertNull(result)
    }

    @Test
    fun `returns null for class containing but not ending with ConstructorCallExpression`() {
        // A class name like "ConstructorCallExpressionWrapper" would match contains()
        // but should NOT match endsWith() - verify the stricter check
        val fakeWrapper = ConstructorCallExpressionWrapper(object {
            val name = "example.Type"
        })
        val result = calculator.calculate(fakeWrapper, context)
        assertNull(result)
    }

    @Test
    fun `returns Known type for mock constructor call`() {
        // Create a mock that matches the expected structure
        val mockType = object {
            val name = "java.util.ArrayList"
        }
        val mockConstructor = MockConstructorCallExpression(mockType)

        val result = calculator.calculate(mockConstructor, context)

        assertEquals(SemanticType.Known("java.util.ArrayList"), result)
    }

    // Mock class with the expected structure (name ends with "ConstructorCallExpression")
    private class MockConstructorCallExpression(val type: Any)

    // Mock class that contains but doesn't end with "ConstructorCallExpression"
    // This simulates a wrapper class that should NOT be matched by the stricter check
    private class ConstructorCallExpressionWrapper(val type: Any)

    private fun mockContext(): TypeContext = object : TypeContext {
        override fun resolveType(fqn: String) = SemanticType.Unknown("not needed")
        override fun calculateType(node: Any) = SemanticType.Unknown("not needed")
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
