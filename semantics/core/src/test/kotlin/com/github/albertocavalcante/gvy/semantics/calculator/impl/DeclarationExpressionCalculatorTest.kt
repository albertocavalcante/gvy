package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeclarationExpressionCalculatorTest {

    private class DeclarationExpression(val rightExpression: Any)

    private class DeclarationExpressionNoRight

    @Test
    fun `returns RHS type for DeclarationExpression`() {
        val calculator = DeclarationExpressionCalculator()
        val rhs = Any()

        val result = calculator.calculate(DeclarationExpression(rhs), mockContext(mapOf(rhs to TypeConstants.INT)))

        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `returns null when right expression is missing`() {
        val calculator = DeclarationExpressionCalculator()

        val result = calculator.calculate(DeclarationExpressionNoRight(), mockContext(emptyMap()))

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
