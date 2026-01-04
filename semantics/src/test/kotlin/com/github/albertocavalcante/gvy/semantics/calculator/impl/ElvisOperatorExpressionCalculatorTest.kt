package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ElvisOperatorExpressionCalculatorTest {

    private class ElvisOperatorExpression(val booleanExpression: Any, val falseExpression: Any)

    private class NotElvis(val booleanExpression: Any)

    @Test
    fun `returns LUB of booleanExpression and falseExpression`() {
        val calculator = ElvisOperatorExpressionCalculator()
        val left = Any()
        val right = Any()

        val context = mockContext(mapOf(left to TypeConstants.INT, right to TypeConstants.LONG))

        val result = calculator.calculate(ElvisOperatorExpression(left, right), context)

        // INT vs LONG -> LONG
        assertEquals(TypeConstants.LONG, result)
    }

    @Test
    fun `returns null when false expression is missing`() {
        val calculator = ElvisOperatorExpressionCalculator()

        val result = calculator.calculate(NotElvis(Any()), mockContext(emptyMap()))

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
