package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
}
