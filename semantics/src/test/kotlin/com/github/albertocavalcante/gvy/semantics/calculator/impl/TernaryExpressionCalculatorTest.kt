package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TernaryExpressionCalculatorTest {

    private class TernaryExpression(val trueExpression: Any, val falseExpression: Any)

    private class NotATernary(val trueExpression: Any)

    @Test
    fun `returns LUB of true and false expressions`() {
        val calculator = TernaryExpressionCalculator()
        val t = Any()
        val f = Any()

        val context = mockContext(mapOf(t to TypeConstants.INT, f to TypeConstants.LONG))

        val result = calculator.calculate(TernaryExpression(t, f), context)

        // INT vs LONG -> LONG
        assertEquals(TypeConstants.LONG, result)
    }

    @Test
    fun `returns null when false expression is missing`() {
        val calculator = TernaryExpressionCalculator()

        val result = calculator.calculate(NotATernary(Any()), mockContext(emptyMap()))

        assertNull(result)
    }

    private fun mockContext(types: Map<Any, SemanticType>): TypeContext = object : TypeContext {
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
