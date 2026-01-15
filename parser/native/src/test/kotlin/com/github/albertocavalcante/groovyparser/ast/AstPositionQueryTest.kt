package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class AstPositionQueryTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `find constructor call type name`() {
        val code = """
            package com.example

            import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration

            class DependencyTest {
                def test() {
                    new GenericPipelineDeclaration()
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val targetLine = code.lines().indexOfFirst { it.contains("new GenericPipelineDeclaration") }
        assertTrue(targetLine >= 0, "Expected constructor call line to exist")

        val col = code.lines()[targetLine].indexOf("GenericPipelineDeclaration")
        assertTrue(col >= 0, "Expected type name on constructor call line")

        val node = visitor.getNodeAt(uri, targetLine, col + 5) // inside identifier
        assertNotNull(node, "Should find node at constructor type position")

        val isConstructorOrType =
            node is org.codehaus.groovy.ast.expr.ConstructorCallExpression ||
                (node is org.codehaus.groovy.ast.ClassNode && node.nameWithoutPackage == "GenericPipelineDeclaration")

        assertTrue(
            isConstructorOrType,
            "Expected ConstructorCallExpression or referenced ClassNode but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find node at import class name`() {
        val code = """
            import org.junit.Test

            class C { }
        """.trimIndent()

        val result = fixture.parse(code)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val importLine = code.lines()[0]
        val col = importLine.indexOf("Test")
        assertTrue(col >= 0, "Expected to find imported class name on import line")

        val node = visitor.getNodeAt(uri, 0, col + 2) // inside the identifier, not just the first char
        assertNotNull(node, "Should find node at import class name position")
        assertTrue(
            node is org.codehaus.groovy.ast.ClassNode || node is org.codehaus.groovy.ast.ImportNode,
            "Expected ClassNode or ImportNode but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find node at extends type name`() {
        val code = """
            import com.lesfurets.jenkins.unit.BasePipelineTest
            
            class TestExampleJob extends BasePipelineTest { }
        """.trimIndent()

        val result = fixture.parse(code)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val extendsLine = code.lines()[2]
        val col = extendsLine.indexOf("BasePipelineTest")
        assertTrue(col >= 0, "Expected to find extends type name on class line")

        val node = visitor.getNodeAt(uri, 2, col + 4) // inside the identifier, not just the first char
        assertNotNull(node, "Should find node at extends type name position")

        // We specifically want the referenced type node (ClassNode) so definition can resolve it.
        assertTrue(
            node is org.codehaus.groovy.ast.ClassNode,
            "Expected ClassNode at extends type name but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find binary expression at position`() {
        val code = """
            def x = 1 + 2
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        // Traverse AST to find the BinaryExpression "1 + 2"
        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val decl = stmt.expression as org.codehaus.groovy.ast.expr.DeclarationExpression
        val binaryExpr = decl.rightExpression as BinaryExpression

        assertTrue(binaryExpr.lineNumber > 0)

        // Query at the BinaryExpression start - this will find the most specific node (likely a child)
        val startLine = binaryExpr.lineNumber - 1
        val startCol = binaryExpr.columnNumber - 1
        val startNode = visitor.getNodeAt(uri, startLine, startCol)
        assertNotNull(startNode, "Should find node at binary expression start")
        // At the start position, we'll find the left operand (ConstantExpression "1")
        assertTrue(
            startNode is org.codehaus.groovy.ast.expr.ConstantExpression,
            "At start of '1 + 2', should find left operand (ConstantExpression)",
        )

        // Query inside the binary expression (after the operand) to find the BinaryExpression itself
        // The "+" operator is at a later column
        val operatorCol = startCol + 2 // After "1 "
        val operatorNode = visitor.getNodeAt(uri, startLine, operatorCol)
        assertNotNull(operatorNode, "Should find node at operator position")
        assertTrue(
            operatorNode is BinaryExpression,
            "At operator '+' position, should find BinaryExpression but got ${operatorNode?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find closure expression at position`() {
        val code = """
            def c = { println "hi" }
        """.trimIndent()

        val result = fixture.parse(code)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val decl = stmt.expression as org.codehaus.groovy.ast.expr.DeclarationExpression
        val closureExpr = decl.rightExpression as ClosureExpression

        val queryLine = closureExpr.lineNumber - 1
        val queryCol = closureExpr.columnNumber - 1

        val node = visitor.getNodeAt(uri, queryLine, queryCol)

        assertNotNull(node)
        assertTrue(
            node is ClosureExpression || node is BlockStatement,
            "Expected Closure or Block but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find gstring expression`() {
        val name = "world"
        val code = "def s = \"hello \$name\""

        val result = fixture.parse(code)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val decl = stmt.expression as org.codehaus.groovy.ast.expr.DeclarationExpression
        val gstringExpr = decl.rightExpression as GStringExpression

        val queryLine = gstringExpr.lineNumber - 1
        val queryCol = gstringExpr.columnNumber - 1

        // GStrings contain multiple parts (strings and values)
        // At the start position, we might find a more specific child node
        val node = visitor.getNodeAt(uri, queryLine, queryCol)
        assertNotNull(node, "Should find a node at GString position")

        // The node should be either the GString itself or one of its parts (String or Value)
        val isGStringOrPart = node is GStringExpression ||
            node is org.codehaus.groovy.ast.expr.ConstantExpression ||
            node is org.codehaus.groovy.ast.expr.VariableExpression

        assertTrue(
            isGStringOrPart,
            "At GString position, should find GString or its components, but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find method call expression`() {
        val code = "println(1, 2)"

        val result = fixture.parse(code)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val methodCall = stmt.expression as MethodCallExpression

        val queryLine = methodCall.lineNumber - 1
        val queryCol = methodCall.columnNumber - 1

        // At the start of a method call, we'll find a VariableExpression
        // This could be "this" (implicit receiver) or the method name
        val node = visitor.getNodeAt(uri, queryLine, queryCol)
        assertNotNull(node, "Should find node at method call position")

        // The most specific node should be either a Variable, MethodCall, or part of the call
        assertTrue(
            node is org.codehaus.groovy.ast.expr.VariableExpression ||
                node is MethodCallExpression ||
                node is org.codehaus.groovy.ast.expr.ConstantExpression,
            "At method call position, should find a call-related node, but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find method call with explicit receiver inside method body`() {
        // This test case simulates the user's issue:
        // helper.registerMethod("test") inside a method body
        val code = """
            class TestClass {
                def helper = [:]

                void setUp() {
                    helper.registerMethod("test")
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        // Find the line containing the method call
        val targetLine = code.lines().indexOfFirst { it.contains("registerMethod") }
        assertTrue(targetLine >= 0, "Expected to find registerMethod line")

        val lineContent = code.lines()[targetLine]
        val methodNameCol = lineContent.indexOf("registerMethod")
        assertTrue(methodNameCol >= 0, "Expected to find registerMethod in line")

        // Manually verify AST structure to understand what Groovy produces
        val classNode = result.ast!!.classes.find { it.name == "TestClass" }
        assertNotNull(classNode, "TestClass should be parsed")

        val setUpMethod = classNode!!.getDeclaredMethod("setUp", arrayOf())
        assertNotNull(setUpMethod, "setUp method should exist")

        val block = setUpMethod!!.code as? BlockStatement
        assertNotNull(block, "setUp should have a block body")

        val stmt = block!!.statements.firstOrNull() as? ExpressionStatement
        assertNotNull(stmt, "Block should have a statement")

        val methodCall = stmt!!.expression as? MethodCallExpression
        assertNotNull(methodCall, "Statement should be a method call")

        // Log position info for debugging
        println("MethodCallExpression position info:")
        println("  lineNumber: ${methodCall!!.lineNumber}")
        println("  columnNumber: ${methodCall.columnNumber}")
        println("  lastLineNumber: ${methodCall.lastLineNumber}")
        println("  lastColumnNumber: ${methodCall.lastColumnNumber}")
        println("  method.lineNumber: ${methodCall.method.lineNumber}")
        println("  method.columnNumber: ${methodCall.method.columnNumber}")
        println("  objectExpression.lineNumber: ${methodCall.objectExpression.lineNumber}")
        println("  objectExpression.columnNumber: ${methodCall.objectExpression.columnNumber}")

        // Query at the method name position
        val node = visitor.getNodeAt(uri, targetLine, methodNameCol + 3)
        assertNotNull(node, "Should find node at method name position")

        // We expect MethodCallExpression, NOT MethodNode
        assertTrue(
            node is MethodCallExpression,
            "Expected MethodCallExpression at method name position, but got ${node?.javaClass?.simpleName}",
        )
    }

    // =========================================================================
    // Method signature type reference tests - these should return ClassNode
    // =========================================================================

    @Test
    fun `find node at throws clause type - should return ClassNode not MethodNode`() {
        // This is the exact bug reported: hovering on Exception shows method info
        val code = """
            class MyTest {
                void setUp() throws Exception {
                    println "setup"
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful, "Code should parse successfully")
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        // Find "Exception" position on the throws clause line
        val throwsLine = code.lines().indexOfFirst { it.contains("throws Exception") }
        assertTrue(throwsLine >= 0, "Expected to find throws clause line")

        val lineContent = code.lines()[throwsLine]
        val exceptionCol = lineContent.indexOf("Exception")
        assertTrue(exceptionCol >= 0, "Expected to find Exception in throws clause")

        // Query at "Exception" position (inside the identifier)
        val node = visitor.getNodeAt(uri, throwsLine, exceptionCol + 3)
        assertNotNull(node, "Should find node at throws clause type position")

        // THIS IS THE BUG: We get MethodNode when we should get ClassNode
        assertTrue(
            node is ClassNode,
            "Expected ClassNode at throws clause type, but got ${node?.javaClass?.simpleName}. " +
                "Hovering on 'Exception' should show Exception class info, not the method info!",
        )

        // Verify it's the Exception type, not the containing class
        if (node is ClassNode) {
            assertTrue(
                node.name.contains("Exception"),
                "Expected Exception ClassNode but got ${node.name}",
            )
        }
    }

    @Test
    fun `find node at return type - should return ClassNode not MethodNode`() {
        val code = """
            class MyClass {
                String getName() {
                    return "test"
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        // Find "String" return type position
        val methodLine = code.lines().indexOfFirst { it.contains("String getName") }
        assertTrue(methodLine >= 0, "Expected to find method declaration line")

        val lineContent = code.lines()[methodLine]
        val stringCol = lineContent.indexOf("String")
        assertTrue(stringCol >= 0, "Expected to find String return type")

        // Query at "String" position
        val node = visitor.getNodeAt(uri, methodLine, stringCol + 2)
        assertNotNull(node, "Should find node at return type position")

        // We want ClassNode for "String", not MethodNode for "getName"
        assertTrue(
            node is ClassNode,
            "Expected ClassNode at return type, but got ${node?.javaClass?.simpleName}. " +
                "Hovering on return type should show type info!",
        )
    }

    // TODO: Fix parameter type position overlap - see https://github.com/albertocavalcante/gvy/issues/865
    // Groovy AST reports parameter type positions that overlap with parameter names,
    // causing position-based queries to return the wrong node (Parameter instead of ClassNode).
    @Disabled("Parameter type positions overlap with parameter names in Groovy AST - see issue #865")
    @Test
    fun `find node at parameter type - should return ClassNode not Parameter`() {
        val code = """
            class MyClass {
                void process(String input, Integer count) {
                    println input
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        // Find parameter types
        val methodLine = code.lines().indexOfFirst { it.contains("void process") }
        assertTrue(methodLine >= 0, "Expected to find method declaration line")

        val lineContent = code.lines()[methodLine]

        // Test first parameter type: String
        val stringCol = lineContent.indexOf("String")
        assertTrue(stringCol >= 0, "Expected to find String parameter type")

        val stringNode = visitor.getNodeAt(uri, methodLine, stringCol + 2)
        assertNotNull(stringNode, "Should find node at String parameter type position")
        assertTrue(
            stringNode is ClassNode,
            "Expected ClassNode at parameter type 'String', but got ${stringNode?.javaClass?.simpleName}",
        )

        // Test second parameter type: Integer
        val integerCol = lineContent.indexOf("Integer")
        assertTrue(integerCol >= 0, "Expected to find Integer parameter type")

        val integerNode = visitor.getNodeAt(uri, methodLine, integerCol + 2)
        assertNotNull(integerNode, "Should find node at Integer parameter type position")
        assertTrue(
            integerNode is ClassNode,
            "Expected ClassNode at parameter type 'Integer', but got ${integerNode?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find node at parameter name - should return Parameter not ClassNode`() {
        // Counterpart test: when hovering on parameter NAME, we want Parameter node
        val code = """
            class MyClass {
                void process(String input) {
                    println input
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodLine = code.lines().indexOfFirst { it.contains("void process") }
        val lineContent = code.lines()[methodLine]

        // Find "input" parameter name (after "String ")
        val inputCol = lineContent.indexOf("input")
        assertTrue(inputCol >= 0, "Expected to find 'input' parameter name")

        val node = visitor.getNodeAt(uri, methodLine, inputCol + 2)
        assertNotNull(node, "Should find node at parameter name position")

        // At parameter name, we want Parameter or VariableExpression, not ClassNode
        assertTrue(
            node is org.codehaus.groovy.ast.Parameter,
            "Expected Parameter at parameter name 'input', but got ${node?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find node at multiple throws clause types`() {
        val code = """
            import java.io.IOException

            class MyClass {
                void riskyMethod() throws IOException, IllegalArgumentException {
                    throw new IOException()
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodLine = code.lines().indexOfFirst { it.contains("throws IOException") }
        val lineContent = code.lines()[methodLine]

        // Test first exception type: IOException
        val ioCol = lineContent.indexOf("IOException")
        assertTrue(ioCol >= 0, "Expected to find IOException")

        val ioNode = visitor.getNodeAt(uri, methodLine, ioCol + 3)
        assertNotNull(ioNode, "Should find node at IOException position")
        assertTrue(
            ioNode is ClassNode,
            "Expected ClassNode at 'IOException', but got ${ioNode?.javaClass?.simpleName}",
        )

        // Test second exception type: IllegalArgumentException
        val illegalCol = lineContent.indexOf("IllegalArgumentException")
        assertTrue(illegalCol >= 0, "Expected to find IllegalArgumentException")

        val illegalNode = visitor.getNodeAt(uri, methodLine, illegalCol + 5)
        assertNotNull(illegalNode, "Should find node at IllegalArgumentException position")
        assertTrue(
            illegalNode is ClassNode,
            "Expected ClassNode at 'IllegalArgumentException', but got ${illegalNode?.javaClass?.simpleName}",
        )
    }

    @Test
    fun `find node at field type - should return ClassNode not FieldNode`() {
        val code = """
            class MyClass {
                String name
                Integer count
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astModel
        val uri = java.net.URI.create("file:///Test.groovy")

        // Find String field type
        val fieldLine = code.lines().indexOfFirst { it.contains("String name") }
        assertTrue(fieldLine >= 0, "Expected to find field declaration line")

        val lineContent = code.lines()[fieldLine]
        val stringCol = lineContent.indexOf("String")
        assertTrue(stringCol >= 0, "Expected to find String field type")

        val node = visitor.getNodeAt(uri, fieldLine, stringCol + 2)
        assertNotNull(node, "Should find node at field type position")

        // We want ClassNode for "String", not FieldNode
        assertTrue(
            node is ClassNode,
            "Expected ClassNode at field type 'String', but got ${node?.javaClass?.simpleName}. " +
                "Hovering on field type should show type info!",
        )
    }
}
