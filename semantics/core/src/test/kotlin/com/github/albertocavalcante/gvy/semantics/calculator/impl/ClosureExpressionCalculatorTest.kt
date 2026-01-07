package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.testContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ClosureExpressionCalculatorTest {

    private class ClosureExpression

    private class NotAClosure

    @Test
    fun `returns Closure type for ClosureExpression`() {
        val calculator = ClosureExpressionCalculator()

        val result = calculator.calculate(ClosureExpression(), testContext())

        assertEquals(TypeConstants.CLOSURE, result)
    }

    @Test
    fun `returns null for non-closure nodes`() {
        val calculator = ClosureExpressionCalculator()

        val result = calculator.calculate(NotAClosure(), testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult should return Right with Closure type for ClosureExpression`() {
        val calculator = ClosureExpressionCalculator()
        val node = ClosureExpression()

        val result = calculator.calculateResult(node, testContext())

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type -> assertEquals(TypeConstants.CLOSURE, type) },
        )
    }

    @Test
    fun `calculateResult should return Left with UnsupportedNode for non-closure nodes`() {
        val calculator = ClosureExpressionCalculator()
        val node = NotAClosure()

        val result = calculator.calculateResult(node, testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(error.reason.contains("NotAClosure"))
            },
            ifRight = { fail("Expected Left but got Right($it)") },
        )
    }
}
