package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ConstantExpressionCalculatorTest {

    // Test double simulating Groovy's ConstantExpression
    data class TestConstant(val value: Any?)

    data class NoValueProperty(val other: Any?)

    @Test
    fun `should calculate Integer type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(42)
        val result = calculator.calculate(node, mockContext())

        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `should calculate String type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant("hello")
        val result = calculator.calculate(node, mockContext())

        assertEquals(TypeConstants.STRING, result)
    }

    @Test
    fun `should calculate Boolean type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(true)
        val result = calculator.calculate(node, mockContext())

        assertEquals(TypeConstants.BOOLEAN, result)
    }

    @Test
    fun `should calculate BigDecimal type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(BigDecimal("3.14"))
        val result = calculator.calculate(node, mockContext())

        assertEquals(TypeConstants.BIG_DECIMAL, result)
    }

    @Test
    fun `should calculate Null type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(null)
        val result = calculator.calculate(node, mockContext())

        assertEquals(SemanticType.Null, result)
    }

    @Test
    fun `should return null when node has no value property`() {
        val calculator = ConstantExpressionCalculator()
        val node = NoValueProperty("x")

        val result = calculator.calculate(node, mockContext())

        assertNull(result)
    }

    private fun mockContext() = object : TypeContext {
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
