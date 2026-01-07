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

class GStringExpressionCalculatorTest {

    private class GStringExpression(val strings: List<Any>, val values: List<Any>)

    private class NotAGString(val strings: List<Any>)

    @Test
    fun `returns GString type when strings and values are present`() {
        val calculator = GStringExpressionCalculator()

        val result = calculator.calculate(GStringExpression(listOf("a"), listOf(1)), testContext())

        assertEquals(TypeConstants.GSTRING, result)
    }

    @Test
    fun `returns null when values are missing`() {
        val calculator = GStringExpressionCalculator()

        val result = calculator.calculate(NotAGString(listOf("a")), testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult returns Right with GString type when strings and values are present`() {
        val calculator = GStringExpressionCalculator()

        val result = calculator.calculateResult(GStringExpression(listOf("a"), listOf(1)), testContext())

        assertTrue(result.isRight())
        assertEquals(TypeConstants.GSTRING, result.getOrNull())
    }

    @Test
    fun `calculateResult returns Left with UnsupportedNode when values are missing`() {
        val calculator = GStringExpressionCalculator()

        val result = calculator.calculateResult(NotAGString(listOf("a")), testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(error.reason.contains("NotAGString"))
                assertTrue(error.reason.contains("missing strings/values properties"))
            },
            ifRight = { fail("Expected Left but got Right($it)") },
        )
    }
}
