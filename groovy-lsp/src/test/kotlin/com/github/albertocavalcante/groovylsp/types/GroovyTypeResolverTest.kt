package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroovyTypeResolverTest {

    private lateinit var typeResolver: GroovyTypeResolver

    @BeforeEach
    fun setUp() {
        typeResolver = GroovyTypeResolver()
    }

    @Test
    fun `resolveType for FieldNode returns field type`() = runTest {
        // Given
        val intType = ClassHelper.int_TYPE
        val field = FieldNode("testField", 0, intType, null, null)
        val dummyContext = createMinimalContext()

        // When
        val result = typeResolver.resolveType(field, dummyContext)

        // Then
        assertEquals(intType, result)
    }

    @Test
    fun `resolveType for MethodNode returns return type`() = runTest {
        // Given
        val booleanType = ClassHelper.boolean_TYPE
        val method = MethodNode("testMethod", 0, booleanType, emptyArray(), emptyArray(), null)
        val dummyContext = createMinimalContext()

        // When
        val result = typeResolver.resolveType(method, dummyContext)

        // Then
        assertEquals(booleanType, result)
    }

    @Test
    fun `resolveType for Parameter returns parameter type`() = runTest {
        // Given
        val doubleType = ClassHelper.double_TYPE
        val parameter = Parameter(doubleType, "param")
        val dummyContext = createMinimalContext()

        // When
        val result = typeResolver.resolveType(parameter, dummyContext)

        // Then
        assertEquals(doubleType, result)
    }

    @Test
    fun `resolveClassLocation for primitive type returns null`() = runTest {
        // Given
        val primitiveType = ClassHelper.int_TYPE
        val dummyContext = createMinimalContext()

        // When
        val result = typeResolver.resolveClassLocation(primitiveType, dummyContext)

        // Then
        assertNull(result)
    }

    private fun createMinimalContext(): CompilationContext {
        // Create a minimal context for testing - we can't easily mock everything
        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)
        val source = StringReaderSource("// test", config)
        val sourceUnit = SourceUnit("test.groovy", source, config, classLoader, compilationUnit.errorCollector)
        val moduleNode = ModuleNode(sourceUnit)
        val astVisitor = RecursiveAstVisitor(NodeRelationshipTracker())

        return CompilationContext(
            uri = URI.create("file:///test.groovy"),
            moduleNode = moduleNode,
            astModel = astVisitor,
            workspaceRoot = null,
        )
    }
}

/**
 * Test for the TypeCalculator chain of responsibility pattern.
 */
class GroovyTypeCalculatorTest {

    private lateinit var typeCalculator: GroovyTypeCalculator

    @BeforeEach
    fun setUp() {
        typeCalculator = GroovyTypeCalculator()
    }

    @Test
    fun `calculators are called in priority order`() = runTest {
        // Given
        val highPriorityCalculator = object : TypeCalculator {
            override val priority = 100
            override suspend fun calculateType(
                expression: org.codehaus.groovy.ast.expr.Expression,
                context: CompilationContext,
            ): ClassNode? = ClassHelper.STRING_TYPE
        }

        val lowPriorityCalculator = object : TypeCalculator {
            override val priority = -100
            override suspend fun calculateType(
                expression: org.codehaus.groovy.ast.expr.Expression,
                context: CompilationContext,
            ): ClassNode? = ClassHelper.int_TYPE
        }

        typeCalculator.register(lowPriorityCalculator)
        typeCalculator.register(highPriorityCalculator)

        val expression = VariableExpression("test") // Use real expression
        val dummyContext = createDummyContext()

        // When
        val result = typeCalculator.calculateType(expression, dummyContext)

        // Then
        assertEquals(ClassHelper.STRING_TYPE, result) // High priority calculator should win
    }

    @Test
    fun `returns null when no calculator can handle expression`() = runTest {
        // Given
        val calculator = object : TypeCalculator {
            override suspend fun calculateType(
                expression: org.codehaus.groovy.ast.expr.Expression,
                context: CompilationContext,
            ): ClassNode? = null
        }

        typeCalculator.register(calculator)
        val expression = VariableExpression("test")
        val dummyContext = createDummyContext()

        // When
        val result = typeCalculator.calculateType(expression, dummyContext)

        // Then
        assertNull(result)
    }

    private fun createDummyContext(): CompilationContext {
        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)
        val source = StringReaderSource("// test", config)
        val sourceUnit = SourceUnit("test.groovy", source, config, classLoader, compilationUnit.errorCollector)
        val moduleNode = ModuleNode(sourceUnit)
        val astVisitor = RecursiveAstVisitor(NodeRelationshipTracker())

        return CompilationContext(
            uri = URI.create("file:///test.groovy"),
            moduleNode = moduleNode,
            astModel = astVisitor,
            workspaceRoot = null,
        )
    }
}

/**
 * Test for the DefaultTypeCalculator.
 */
class DefaultTypeCalculatorTest {

    private lateinit var calculator: DefaultTypeCalculator

    @BeforeEach
    fun setUp() {
        calculator = DefaultTypeCalculator()
    }

    @Test
    fun `returns expression type when available and not placeholder`() = runTest {
        // Given
        val expression = VariableExpression("test")
        val stringType = ClassHelper.STRING_TYPE
        expression.type = stringType
        val dummyContext = createDummyContext()

        // When
        val result = calculator.calculateType(expression, dummyContext)

        // Then
        assertEquals(stringType, result)
    }

    @Test
    fun `returns null when expression type is null`() = runTest {
        // Given
        val expression = VariableExpression("test")
        // expression.type is null by default
        val dummyContext = createDummyContext()

        // When
        val result = calculator.calculateType(expression, dummyContext)

        // Then
        assertNull(result)
    }

    private fun createDummyContext(): CompilationContext {
        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)
        val source = StringReaderSource("// test", config)
        val sourceUnit = SourceUnit("test.groovy", source, config, classLoader, compilationUnit.errorCollector)
        val moduleNode = ModuleNode(sourceUnit)
        val astVisitor = RecursiveAstVisitor(NodeRelationshipTracker())

        return CompilationContext(
            uri = URI.create("file:///test.groovy"),
            moduleNode = moduleNode,
            astModel = astVisitor,
            workspaceRoot = null,
        )
    }
}
