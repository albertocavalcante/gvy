package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class TypeCalculatorRegistryTest {

    data class TestNode(val name: String)
    data class SubNode(val value: Int)

    class TestNodeCalculator(override val priority: Int = 0) : TypeCalculator<TestNode> {
        override val nodeType: KClass<TestNode> = TestNode::class

        override fun calculate(node: TestNode, context: TypeContext): SemanticType? {
            if (node.name == "unknown") return null
            return SemanticType.Known("TestType", emptyList())
        }
    }

    class SubNodeCalculator : TypeCalculator<SubNode> {
        override val nodeType: KClass<SubNode> = SubNode::class

        override fun calculate(node: SubNode, context: TypeContext): SemanticType? =
            SemanticType.Known("SubType", emptyList())
    }

    @Test
    fun `should calculate type using registered calculator`() {
        val registry = TypeCalculatorRegistry.builder()
            .register(TestNodeCalculator())
            .build()

        val result = registry.calculate(TestNode("test"), mockContext())

        assertTrue(result is SemanticType.Known)
        assertEquals("TestType", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `should return Unknown when no calculator handles the node`() {
        val registry = TypeCalculatorRegistry.builder()
            .register(TestNodeCalculator())
            .build()

        // "unknown" name triggers null return from calculator
        val result = registry.calculate(TestNode("unknown"), mockContext())

        assertTrue(result is SemanticType.Unknown)
    }

    @Test
    fun `should return Unknown when node type has no registered calculator`() {
        val registry = TypeCalculatorRegistry.builder()
            .build()

        val result = registry.calculate(TestNode("test"), mockContext())

        assertTrue(result is SemanticType.Unknown)
    }

    @Test
    fun `should respect calculator priority`() {
        val highPriority = object : TypeCalculator<TestNode> {
            override val nodeType = TestNode::class
            override val priority = 100
            override fun calculate(node: TestNode, context: TypeContext) = SemanticType.Known("High", emptyList())
        }

        val lowPriority = object : TypeCalculator<TestNode> {
            override val nodeType = TestNode::class
            override val priority = 0
            override fun calculate(node: TestNode, context: TypeContext) = SemanticType.Known("Low", emptyList())
        }

        val registry = TypeCalculatorRegistry.builder()
            .register(lowPriority)
            .register(highPriority)
            .build()

        val result = registry.calculate(TestNode("test"), mockContext())

        assertEquals("High", (result as SemanticType.Known).fqn)
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
