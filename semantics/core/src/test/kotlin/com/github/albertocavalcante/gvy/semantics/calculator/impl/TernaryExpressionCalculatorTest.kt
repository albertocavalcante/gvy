package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.testContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class TernaryExpressionCalculatorTest {

    private class TernaryExpression(val trueExpression: Any, val falseExpression: Any)

    private class NotATernary(val trueExpression: Any)

    @Test
    fun `returns LUB of true and false expressions`() {
        val calculator = TernaryExpressionCalculator()
        val t = Any()
        val f = Any()

        val context = mockContext(mapOf(t to TypeConstants.INT, f to TypeConstants.LONG))

        val result = calculator.calculate(TernaryExpression(t, f), context)

        // INT vs LONG -> LONG
        assertEquals(TypeConstants.LONG, result)
    }

    @Test
    fun `returns null when false expression is missing`() {
        val calculator = TernaryExpressionCalculator()

        val result = calculator.calculate(NotATernary(Any()), testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult should return Right with LUB of branches`() {
        val calculator = TernaryExpressionCalculator()
        val t = Any()
        val f = Any()

        val context = mockContext(mapOf(t to TypeConstants.INT, f to TypeConstants.LONG))

        val result = calculator.calculateResult(TernaryExpression(t, f), context)

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type -> assertEquals(TypeConstants.LONG, type) },
        )
    }

    @Test
    fun `calculateResult should return Left when false expression is missing`() {
        val calculator = TernaryExpressionCalculator()

        val result = calculator.calculateResult(NotATernary(Any()), testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(error.reason.contains("falseExpression"))
            },
            ifRight = { fail("Expected Left but got Right($it)") },
        )
    }

    @Test
    fun `calculateResult should propagate error from failing true branch`() {
        val calculator = TernaryExpressionCalculator()
        val failingContext = testContext(failOnCalculateType = true)

        val node = TernaryExpression(object {}, object {})

        val result = calculator.calculateResult(node, failingContext)

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.SymbolNotFound)
            },
            ifRight = { fail("Expected Left (error propagation) but got Right($it)") },
        )
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
