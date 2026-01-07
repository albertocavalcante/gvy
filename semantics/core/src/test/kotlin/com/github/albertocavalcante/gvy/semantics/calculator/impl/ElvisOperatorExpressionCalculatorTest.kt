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

class ElvisOperatorExpressionCalculatorTest {

    private class ElvisOperatorExpression(val booleanExpression: Any, val falseExpression: Any)

    private class NotElvis(val booleanExpression: Any)

    @Test
    fun `returns LUB of booleanExpression and falseExpression`() {
        val calculator = ElvisOperatorExpressionCalculator()
        val left = Any()
        val right = Any()

        val context = mockContext(mapOf(left to TypeConstants.INT, right to TypeConstants.LONG))

        val result = calculator.calculate(ElvisOperatorExpression(left, right), context)

        // INT vs LONG -> LONG
        assertEquals(TypeConstants.LONG, result)
    }

    @Test
    fun `returns null when false expression is missing`() {
        val calculator = ElvisOperatorExpressionCalculator()

        val result = calculator.calculate(NotElvis(Any()), testContext())

        assertNull(result)
    }

    // Tests for Either-based calculateResult method

    @Test
    fun `calculateResult should return Right with LUB of branches`() {
        val calculator = ElvisOperatorExpressionCalculator()
        val left = Any()
        val right = Any()

        val context = mockContext(mapOf(left to TypeConstants.INT, right to TypeConstants.LONG))

        val result = calculator.calculateResult(ElvisOperatorExpression(left, right), context)

        result.fold(
            ifLeft = { error -> fail("Expected Right but got Left($error)") },
            ifRight = { type -> assertEquals(TypeConstants.LONG, type) },
        )
    }

    @Test
    fun `calculateResult should return Left when false expression is missing`() {
        val calculator = ElvisOperatorExpressionCalculator()

        val result = calculator.calculateResult(NotElvis(Any()), testContext())

        result.fold(
            ifLeft = { error ->
                assertTrue(error is TypeInferenceError.UnsupportedNode)
                assertTrue(error.reason.contains("falseExpression"))
            },
            ifRight = { fail("Expected Left but got Right($it)") },
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
