package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.testContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
        val result = calculator.calculate(node, testContext())

        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `should calculate String type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant("hello")
        val result = calculator.calculate(node, testContext())

        assertEquals(TypeConstants.STRING, result)
    }

    @Test
    fun `should calculate Boolean type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(true)
        val result = calculator.calculate(node, testContext())

        assertEquals(TypeConstants.BOOLEAN, result)
    }

    @Test
    fun `should calculate BigDecimal type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(BigDecimal("3.14"))
        val result = calculator.calculate(node, testContext())

        assertEquals(TypeConstants.BIG_DECIMAL, result)
    }

    @Test
    fun `should calculate Null type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(null)
        val result = calculator.calculate(node, testContext())

        assertEquals(SemanticType.Null, result)
    }

    @Test
    fun `should return null when node has no value property`() {
        val calculator = ConstantExpressionCalculator()
        val node = NoValueProperty("x")

        val result = calculator.calculate(node, testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult should return Right with Integer type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(42)
        val result = calculator.calculateResult(node, testContext())

        assertTrue(result.isRight())
        assertEquals(TypeConstants.INT, result.getOrNull())
    }

    @Test
    fun `calculateResult should return Right with String type`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant("hello")
        val result = calculator.calculateResult(node, testContext())

        assertTrue(result.isRight())
        assertEquals(TypeConstants.STRING, result.getOrNull())
    }

    @Test
    fun `calculateResult should return Right with Null type for null value`() {
        val calculator = ConstantExpressionCalculator()
        val node = TestConstant(null)
        val result = calculator.calculateResult(node, testContext())

        assertTrue(result.isRight())
        assertEquals(SemanticType.Null, result.getOrNull())
    }

    @Test
    fun `calculateResult should return Left with UnsupportedNode when no value property`() {
        val calculator = ConstantExpressionCalculator()
        val node = NoValueProperty("x")

        val result = calculator.calculateResult(node, testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(error.reason.contains("NoValueProperty"))
            },
            ifRight = { fail("Expected Left but got Right($it)") },
        )
    }
}
