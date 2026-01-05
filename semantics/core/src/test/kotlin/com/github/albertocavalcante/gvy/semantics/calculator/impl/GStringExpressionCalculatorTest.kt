package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GStringExpressionCalculatorTest {

    private class GStringExpression(val strings: List<Any>, val values: List<Any>)

    private class NotAGString(val strings: List<Any>)

    @Test
    fun `returns GString type when strings and values are present`() {
        val calculator = GStringExpressionCalculator()

        val result = calculator.calculate(GStringExpression(listOf("a"), listOf(1)), mockContext())

        assertEquals(TypeConstants.GSTRING, result)
    }

    @Test
    fun `returns null when values are missing`() {
        val calculator = GStringExpressionCalculator()

        val result = calculator.calculate(NotAGString(listOf("a")), mockContext())

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
