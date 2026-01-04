package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClosureExpressionCalculatorTest {

    private class ClosureExpression

    private class NotAClosure

    @Test
    fun `returns Closure type for ClosureExpression`() {
        val calculator = ClosureExpressionCalculator()

        val result = calculator.calculate(ClosureExpression(), mockContext())

        assertEquals(TypeConstants.CLOSURE, result)
    }

    @Test
    fun `returns null for non-closure nodes`() {
        val calculator = ClosureExpressionCalculator()

        val result = calculator.calculate(NotAClosure(), mockContext())

        assertNull(result)
    }

    private fun mockContext(): TypeContext = object : TypeContext {
        override fun resolveType(fqn: String) = SemanticType.Unknown("Mock")
        override fun calculateType(node: Any) = SemanticType.Unknown("Mock")
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
