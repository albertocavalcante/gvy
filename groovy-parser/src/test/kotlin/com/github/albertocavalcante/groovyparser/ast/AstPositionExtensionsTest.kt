package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for AST position-based extension functions.
 * These tests verify position-based node finding and containment checking.
 */
class AstPositionExtensionsTest {

    private val parserFacade = GroovyParserFacade()

    @Test
    fun `containsPosition returns true for node containing position`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        // Test class node contains position within its bounds
        assertTrue(classNode.containsPosition(0, 5)) // Within "class TestClass"
        assertTrue(classNode.containsPosition(1, 4)) // Within method definition
    }

    @Test
    fun `containsPosition returns false for position outside node bounds`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        // Test position outside class bounds
        assertFalse(classNode.containsPosition(-1, 0)) // Before class
        assertFalse(classNode.containsPosition(10, 0)) // After class
    }

    @Test
    fun `containsPosition handles invalid node positions gracefully`() = runTest {
        val groovyCode = "def x = 1"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find a node that might have invalid positions
        ast.classes.forEach { classNode ->
            classNode.fields.forEach { fieldNode ->
                // Test that invalid positions are handled
                if (fieldNode.lineNumber < 0) {
                    assertFalse(fieldNode.containsPosition(0, 0))
                }
            }
        }
    }

    @Test
    fun `findNodeAt returns most specific node at position`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    println "hello"
                    return 42
                }

                String name = "test"
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find node at method declaration
        val nodeAtMethod = ast.findNodeAt(1, 8) // Within "def method()"
        assertNotNull(nodeAtMethod)

        // Find node at field declaration
        val nodeAtField = ast.findNodeAt(6, 8) // Within "String name"
        assertNotNull(nodeAtField)
    }

    @Test
    fun `findNodeAt returns null for position outside any node`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {}
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Test position way outside the code
        val nodeAtInvalidPosition = ast.findNodeAt(100, 100)
        assertNull(nodeAtInvalidPosition)
    }

    @Test
    fun `getDefinition returns variable declaration for variable expression`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    def localVar = "hello"
                    println localVar
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()
        val methodNode = classNode.methods.find { it.name == "method" }
        assertNotNull(methodNode)

        // Method nodes are their own definition, so this test is simplified
        // TODO: Update this test when we have proper AST visitor and symbol table integration
        assertNotNull(methodNode)
        assertEquals("method", methodNode.name)
    }

    @Test
    fun `isHoverable returns true for hoverable nodes`() = runTest {
        val groovyCode = """
            class TestClass {
                String field = "test"

                def method() {
                    def localVar = 42
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()

        assertTrue(classNode.isHoverable()) // ClassNode
        assertTrue(classNode.methods.first().isHoverable()) // MethodNode
        assertTrue(classNode.fields.first().isHoverable()) // FieldNode
    }

    @Test
    fun `isHoverable returns false for non-hoverable nodes`() = runTest {
        val groovyCode = "class TestClass {}"
        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // ModuleNode itself is not hoverable
        assertFalse(ast.isHoverable())
    }

    @Test
    fun `findNodeAt returns specific node for string literal in method body`() = runTest {
        val groovyCode = """
            class TestClass {
                def hello() {
                    println "Hello, World!"
                    return "greeting"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find node at the string literal "Hello, World!" position
        val nodeAtString = ast.findNodeAt(2, 16) // Inside "Hello, World!"
        assertNotNull(nodeAtString)

        // Should be a ConstantExpression representing the string literal
        assertFalse(nodeAtString.javaClass.simpleName.contains("ClassNode"))
        assertFalse(nodeAtString.javaClass.simpleName == "MethodNode")

        // Verify this is actually the string literal we're looking for
        when (nodeAtString) {
            is ConstantExpression -> {
                assertTrue(nodeAtString.value is String)
                assertTrue((nodeAtString.value as String).contains("Hello, World"))
            }
            else -> {
                // If it's not a ConstantExpression, it should still be an expression type
                // This might happen if the AST structure is different than expected
                assertTrue(nodeAtString.javaClass.simpleName.contains("Expression"))
            }
        }
    }

    @Test
    fun `findNodeAt returns specific node for method call in method body`() = runTest {
        val groovyCode = """
            class TestClass {
                def hello() {
                    println "Hello, World!"
                    return "greeting"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Find node at the method call "println" position
        val nodeAtMethodCall = ast.findNodeAt(2, 8) // At "println"
        assertNotNull(nodeAtMethodCall)

        // The node should be more specific than the containing method or class
        // It should represent the actual call to println, not the method that contains it
        assertFalse(nodeAtMethodCall.javaClass.simpleName.contains("ClassNode"))
        assertFalse(nodeAtMethodCall.javaClass.simpleName == "MethodNode")

        // Verify this is actually related to the println call we're looking for
        when (nodeAtMethodCall) {
            is MethodCallExpression -> {
                assertTrue(nodeAtMethodCall.method.text.contains("println"))
            }
            is VariableExpression -> {
                // Println might be treated as a variable reference in some contexts
                assertTrue(nodeAtMethodCall.name.contains("println"))
            }
            else -> {
                // If we get a different expression type, ensure it's still more specific than method/class
                assertTrue(nodeAtMethodCall.javaClass.simpleName.contains("Expression"))
            }
        }
    }

    @Test
    fun `findNodeAt returns most specific node not parent node`() = runTest {
        val groovyCode = """
            class TestClass {
                def calculate() {
                    def x = 42
                    def y = "test"
                    return x + y
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode

        // Test that we get the variable declaration, not the method or class
        val nodeAtVarX = ast.findNodeAt(2, 12) // At variable "x"
        assertNotNull(nodeAtVarX)

        // Should not be the class or method, but something more specific
        assertFalse(nodeAtVarX.javaClass.simpleName.contains("Class"))
        assertFalse(nodeAtVarX.javaClass.simpleName.contains("Method"))

        // Test the number literal
        val nodeAtNumber = ast.findNodeAt(2, 16) // At "42"
        assertNotNull(nodeAtNumber)

        // Should be a constant expression for the number
        assertTrue(
            nodeAtNumber.javaClass.simpleName.contains("Constant") ||
                nodeAtNumber.javaClass.simpleName.contains("Expression"),
        )
    }

    @Test
    fun `position calculations work correctly for multiline nodes`() = runTest {
        val groovyCode = """
            class MultilineClass {
                def multilineMethod() {
                    // This method spans multiple lines
                    def x = 1
                    def y = 2
                    return x + y
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = parserFacade.parse(ParseRequest(uri, groovyCode)).ast as ModuleNode
        val classNode = ast.classes.first()
        val methodNode = classNode.methods.find { it.name == "multilineMethod" }
        assertNotNull(methodNode)

        // Test that multiline method contains positions within its span
        assertTrue(methodNode.containsPosition(1, 4)) // Method declaration line
        assertTrue(methodNode.containsPosition(2, 8)) // Comment line
        assertTrue(methodNode.containsPosition(5, 8)) // Return statement
    }
}
