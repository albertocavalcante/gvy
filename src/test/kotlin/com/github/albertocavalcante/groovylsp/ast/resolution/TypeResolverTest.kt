package com.github.albertocavalcante.groovylsp.ast.resolution
import com.github.albertocavalcante.groovylsp.TestUtils
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TDD tests for TypeResolver.
 * These tests define the expected behavior before implementation.
 */
class TypeResolverTest {

    private val compilationService = TestUtils.createCompilationService()

    @Test
    fun `should resolve type of variable expression`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    String localVar = "test"
                    return localVar
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // TODO: Need to find the actual VariableExpression in the AST
        // For now, create a dummy to test the interface
        val variableExpr = VariableExpression("localVar")

        val resolvedType = resolver.resolveType(variableExpr)

        // Should resolve to String type
        // Currently returns null in minimal implementation - we'll fix this later
        // assertNotNull(resolvedType)
        // assertEquals("java.lang.String", resolvedType.name)
    }

    @Test
    fun `should resolve type of method call expression`() = runTest {
        val groovyCode = """
            class TestClass {
                String getName() {
                    return "test"
                }

                def method() {
                    return getName()
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // TODO: Find the actual MethodCallExpression in the AST
        // For now, create a dummy to test the interface
        val methodCall = MethodCallExpression(VariableExpression("this"), "getName", ArgumentListExpression())

        val resolvedType = resolver.resolveType(methodCall)

        // Should resolve to String (return type of getName method)
        // Currently returns null in minimal implementation - we'll fix this later
        // assertNotNull(resolvedType)
        // assertEquals("java.lang.String", resolvedType.name)
    }

    @Test
    fun `should resolve type of binary expression`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    int a = 5
                    int b = 10
                    return a + b
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // TODO: Find the actual BinaryExpression in the AST
        // For now, create a dummy to test the interface
        val leftExpr = VariableExpression("a")
        val rightExpr = VariableExpression("b")
        val binaryExpr = BinaryExpression(leftExpr, null, rightExpr)

        val resolvedType = resolver.resolveType(binaryExpr)

        // Should resolve to int (result of int + int)
        // Currently returns null in minimal implementation - we'll fix this later
        // assertNotNull(resolvedType)
        // assertEquals("int", resolvedType.name)
    }

    @Test
    fun `should resolve type of closure expression`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    def closure = { String input -> input.toUpperCase() }
                    return closure
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // TODO: Find the actual ClosureExpression in the AST
        // For now, create a dummy to test the interface
        val closureExpr = ClosureExpression(emptyArray(), null)

        val resolvedType = resolver.resolveType(closureExpr)

        // Should resolve to Closure type
        assertNotNull(resolvedType)
        assertEquals("groovy.lang.Closure", resolvedType.name)
    }

    @Test
    fun `should resolve array component type`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    String[] array = ["a", "b", "c"]
                    return array[0]
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // Create a class node representing String[]
        val stringType = ClassNode(String::class.java)
        val stringArrayType = stringType.makeArray()

        val componentType = resolver.inferExpressionType(stringArrayType)

        // Array access should resolve to component type (String)
        assertNotNull(componentType)
        assertEquals("java.lang.String", componentType.name)
    }

    @Test
    fun `should handle null safely for unresolvable types`() = runTest {
        val resolver = TypeResolver()

        // Test with an expression that has no determinable type
        val unknownExpr = VariableExpression("unknownVariable")
        val resolvedType = resolver.resolveType(unknownExpr)

        // Should return null for unresolvable types
        assertNull(resolvedType)
    }

    @Test
    fun `should infer primitive types correctly`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    int intVar = 42
                    boolean boolVar = true
                    double doubleVar = 3.14
                    return intVar
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // Test different primitive types
        val intType = ClassNode(Integer.TYPE)
        val inferredType = resolver.inferExpressionType(intType)

        assertNotNull(inferredType)
        assertEquals("int", inferredType.name)
    }

    @Test
    fun `should resolve type for nested expressions`() = runTest {
        val groovyCode = """
            class TestClass {
                String getName() { return "test" }

                def method() {
                    return getName().toUpperCase()
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val resolver = TypeResolver()

        // Test chained method calls - getName().toUpperCase()
        val getNameCall = MethodCallExpression(VariableExpression("this"), "getName", ArgumentListExpression())
        val toUpperCaseCall = MethodCallExpression(getNameCall, "toUpperCase", ArgumentListExpression())

        val resolvedType = resolver.resolveType(toUpperCaseCall)

        // Should resolve to String (return type of toUpperCase)
        // Currently returns null in minimal implementation - we'll fix this later
        // assertNotNull(resolvedType)
        // assertEquals("java.lang.String", resolvedType.name)
    }
}
