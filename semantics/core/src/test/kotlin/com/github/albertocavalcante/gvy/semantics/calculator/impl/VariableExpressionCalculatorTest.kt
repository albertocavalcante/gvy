package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VariableExpressionCalculatorTest {

    private class VariableExpression(val name: String)

    @Test
    fun `returns symbol type from context`() {
        val calculator = VariableExpressionCalculator()
        val node = VariableExpression("x")

        val result = calculator.calculate(node, mockContext(mapOf("x" to TypeConstants.STRING)))

        assertEquals(TypeConstants.STRING, result)
    }

    @Test
    fun `returns null when symbol is missing`() {
        val calculator = VariableExpressionCalculator()
        val node = VariableExpression("missing")

        val result = calculator.calculate(node, mockContext(emptyMap()))

        assertNull(result)
    }

    private fun mockContext(symbols: Map<String, SemanticType>): TypeContext = object : TypeContext {
        override fun resolveType(fqn: String) = SemanticType.Unknown("Mock")
        override fun calculateType(node: Any) = SemanticType.Unknown("Mock")
        override fun lookupSymbol(name: String) = symbols[name]
        override fun getMethodReturnType(
            receiverType: SemanticType,
            methodName: String,
            argumentTypes: List<SemanticType>,
        ) = null

        override fun getFieldType(receiverType: SemanticType, fieldName: String) = null
        override val isStaticCompilation = false
    }
}
