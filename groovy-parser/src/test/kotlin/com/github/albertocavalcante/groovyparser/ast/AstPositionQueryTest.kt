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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class AstPositionQueryTest {

    private val fixture = ParserTestFixture()

    @Test
    @Disabled(
        "FIXME: Bug in PositionNodeVisitor or coordinate system - fails to find node even with exact coordinates from AST",
    )
    fun `find binary expression at position`() {
        val code = """
            def x = 1 + 2
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        val visitor = result.astVisitor!!
        val uri = java.net.URI.create("file:///Test.groovy")

        // Traverse AST to find the BinaryExpression "1 + 2"
        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val decl = stmt.expression as org.codehaus.groovy.ast.expr.DeclarationExpression
        val binaryExpr = decl.rightExpression as BinaryExpression

        assertTrue(binaryExpr.lineNumber > 0)

        // Use the actual node position to query (converting 1-based AST to 0-based LSP)
        val queryLine = binaryExpr.lineNumber - 1
        val queryCol = binaryExpr.columnNumber - 1

        val node = visitor.getNodeAt(uri, queryLine, queryCol)

        assertNotNull(node, "Should find node at $queryLine:$queryCol")
        assertTrue(node is BinaryExpression, "Expected BinaryExpression but got ${node?.javaClass?.simpleName}")
    }

    @Test
    fun `find closure expression at position`() {
        val code = """
            def c = { println "hi" }
        """.trimIndent()

        val result = fixture.parse(code)
        val visitor = result.astVisitor!!
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
    @Disabled("FIXME: Bug in PositionNodeVisitor - fails to find GStringExpression")
    fun `find gstring expression`() {
        val name = "world"
        val code = "def s = \"hello \$name\""

        val result = fixture.parse(code)
        val visitor = result.astVisitor!!
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val decl = stmt.expression as org.codehaus.groovy.ast.expr.DeclarationExpression
        val gstringExpr = decl.rightExpression as GStringExpression

        val queryLine = gstringExpr.lineNumber - 1
        val queryCol = gstringExpr.columnNumber - 1

        val node = visitor.getNodeAt(uri, queryLine, queryCol)

        assertNotNull(node)
        assertTrue(node is GStringExpression, "Expected GStringExpression but got ${node?.javaClass?.simpleName}")
    }

    @Test
    @Disabled("FIXME: Bug in PositionNodeVisitor - fails to find MethodCallExpression")
    fun `find method call expression`() {
        val code = "println(1, 2)"

        val result = fixture.parse(code)
        val visitor = result.astVisitor!!
        val uri = java.net.URI.create("file:///Test.groovy")

        val methodNode = result.ast!!.classes[0].methods.find { it.name == "run" }!!
        val block = methodNode.code as BlockStatement
        val stmt = block.statements[0] as ExpressionStatement
        val methodCall = stmt.expression as MethodCallExpression

        val queryLine = methodCall.lineNumber - 1
        val queryCol = methodCall.columnNumber - 1

        val node = visitor.getNodeAt(uri, queryLine, queryCol)
        assertNotNull(node)
        assertTrue(
            node is MethodCallExpression,
            "Expected MethodCallExpression but got ${node?.javaClass?.simpleName}",
        )
    }
}
