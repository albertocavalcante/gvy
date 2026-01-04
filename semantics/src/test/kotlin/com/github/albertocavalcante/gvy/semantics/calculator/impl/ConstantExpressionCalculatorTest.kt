package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ConstantExpressionCalculatorTest {

    // Test double simulating Groovy's ConstantExpression
    data class TestConstant(val value: Any?)

    @Test
    fun `should calculate Integer type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(42)
        val result = calculator.calculate(node, mockContext())

        assertEquals("java.lang.Integer", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `should calculate String type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant("hello")
        val result = calculator.calculate(node, mockContext())

        assertEquals("java.lang.String", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `should calculate Boolean type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(true)
        val result = calculator.calculate(node, mockContext())

        assertEquals("java.lang.Boolean", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `should calculate BigDecimal type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(BigDecimal("3.14"))
        val result = calculator.calculate(node, mockContext())

        assertEquals("java.math.BigDecimal", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `should calculate Null type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(null)
        val result = calculator.calculate(node, mockContext())

        assertTrue(result is SemanticType.Known)
        assertEquals("java.lang.Object", (result as SemanticType.Known).fqn)
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
