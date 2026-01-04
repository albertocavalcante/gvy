package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BinaryExpressionCalculatorTest {

    // Test doubles
    data class MockToken(val text: String)
    data class MockBinaryExpression(val leftExpression: Any, val rightExpression: Any, val operation: MockToken)

    val intType = SemanticType.Primitive(PrimitiveKind.INT)
    val doubleType = SemanticType.Primitive(PrimitiveKind.DOUBLE)
    val booleanType = SemanticType.Primitive(PrimitiveKind.BOOLEAN)
    val stringType = SemanticType.Known("java.lang.String", emptyList())
    val unknownType = SemanticType.Unknown("unknown")

    @Test
    fun `should calculate arithmetic addition of Ints`() {
        val calculator = BinaryExpressionCalculator()
        val node = MockBinaryExpression("left", "right", MockToken("+"))
        val context = mockContext(intType, intType)

        val result = calculator.calculate(node, context)

        assertEquals(intType, result)
    }

    @Test
    fun `should calculate arithmetic addition of Int and Double`() {
        val calculator = BinaryExpressionCalculator()
        val node = MockBinaryExpression("left", "right", MockToken("+"))
        val context = mockContext(intType, doubleType)

        val result = calculator.calculate(node, context)

        assertEquals(doubleType, result)
    }

    @Test
    fun `should calculate String concatenation`() {
        val calculator = BinaryExpressionCalculator()
        val node = MockBinaryExpression("left", "right", MockToken("+"))
        val context = mockContext(stringType, intType)

        val result = calculator.calculate(node, context)

        assertEquals(stringType, result)
    }

    @Test
    fun `should calculate Boolean equality`() {
        val calculator = BinaryExpressionCalculator()
        // Test ==
        val eqNode = MockBinaryExpression("left", "right", MockToken("=="))
        assertEquals(booleanType, calculator.calculate(eqNode, mockContext(intType, intType)))

        // Test !=
        val neqNode = MockBinaryExpression("left", "right", MockToken("!="))
        assertEquals(booleanType, calculator.calculate(neqNode, mockContext(intType, intType)))
    }

    @Test
    fun `should calculate Relational checks`() {
        val calculator = BinaryExpressionCalculator()
        val ops = listOf("<", ">", "<=", ">=")

        for (op in ops) {
            val node = MockBinaryExpression("left", "right", MockToken(op))
            val result = calculator.calculate(node, mockContext(intType, intType))
            assertEquals(booleanType, result, "Failed for op: $op")
        }
    }

    @Test
    fun `should calculate Logical operators`() {
        val calculator = BinaryExpressionCalculator()
        val ops = listOf("&&", "||")

        for (op in ops) {
            val node = MockBinaryExpression("left", "right", MockToken(op))
            val result = calculator.calculate(node, mockContext(booleanType, booleanType))
            assertEquals(booleanType, result, "Failed for op: $op")
        }
    }

    @Test
    fun `should calculate Groovy pattern and membership operators`() {
        val calculator = BinaryExpressionCalculator()
        val ops = listOf("=~", "==~", "in")

        for (op in ops) {
            val node = MockBinaryExpression("left", "right", MockToken(op))
            val result = calculator.calculate(node, mockContext(stringType, stringType))
            assertEquals(booleanType, result, "Failed for op: $op")
        }
    }

    @Test
    fun `should calculate Groovy spaceship operator`() {
        val calculator = BinaryExpressionCalculator()
        val node = MockBinaryExpression("left", "right", MockToken("<=>"))

        val result = calculator.calculate(node, mockContext(intType, intType))

        assertEquals(intType, result)
    }

    @Test
    fun `should calculate Groovy power operator`() {
        val calculator = BinaryExpressionCalculator()
        val node = MockBinaryExpression("left", "right", MockToken("**"))

        val result = calculator.calculate(node, mockContext(intType, doubleType))

        assertEquals(doubleType, result)
    }

    private fun mockContext(leftType: SemanticType, rightType: SemanticType) = object : TypeContext {
        override fun resolveType(fqn: String) = SemanticType.Unknown("Mock")

        override fun calculateType(node: Any): SemanticType {
            if (node == "left") return leftType
            if (node == "right") return rightType
            return SemanticType.Unknown("Mock")
        }

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
