package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URI

class AstPositionQuerySelectionTest {
    private val uri = URI.create("file:///Selection.groovy")

    @Test
    fun `both overloads use zero based coordinates and include range endpoints`() {
        val node = ASTNode().at()
        val query = queryFor(node)

        assertSame(node, query.getNodeAt(uri, 2, 4))
        assertSame(node, query.getNodeAt(uri, Position(2, 4)))
        assertSame(node, query.getNodeAt(uri, 2, 7))
        assertSame(node, query.getNodeAt(uri, Position(2, 7)))
        assertNull(query.getNodeAt(uri, 2, 3))
        assertNull(query.getNodeAt(uri, 2, 8))
        assertNull(query.getNodeAt(uri, 1, 4))
        assertNull(query.getNodeAt(uri, 3, 4))
    }

    @Test
    fun `queries return null for untracked files empty trackers and invalid nodes`() {
        assertNull(queryFor().getNodeAt(uri, Position(2, 5)))
        assertNull(queryFor(ASTNode().at()).getNodeAt(URI.create("file:///Other.groovy"), 2, 5))
        assertNull(queryFor(ASTNode().at(line = 0)).getNodeAt(uri, 0, 5))
        assertNull(queryFor(ASTNode().at(column = 0)).getNodeAt(uri, 2, 0))
    }

    @Test
    fun `module nodes remain a fallback while non module nodes take precedence`() {
        val module = ModuleNode(SourceUnit.create("Selection.groovy", "")).at(endColumn = 6)
        val statement = BlockStatement().at(endColumn = 20)

        assertSame(module, queryFor(module).getNodeAt(uri, 2, 5))
        assertSame(statement, queryFor(module, statement).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `statement nodes remain a fallback while non statements take precedence`() {
        val statement = BlockStatement().at(endColumn = 6)
        val expression = ConstantExpression("value").at(endColumn = 20)

        assertSame(statement, queryFor(statement).getNodeAt(uri, 2, 5))
        assertSame(expression, queryFor(statement, expression).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `constructor calls exclude embedded class references even when references are smaller`() {
        val type = classNode("Widget").at(column = 6, endColumn = 7)
        val call = ConstructorCallExpression(type, ArgumentListExpression()).at(endColumn = 20)

        assertSame(type, queryFor(type).getNodeAt(uri, 2, 5))
        assertSame(call, queryFor(type, call).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `constructor calls preserve class declarations and classes on other lines`() {
        val declaration = classNode("Widget").at(column = 1, endColumn = 6)
        val call = ConstructorCallExpression(declaration, ArgumentListExpression()).at(endColumn = 20)
        assertSame(declaration, queryFor(declaration, call).getNodeAt(uri, 2, 5))

        val otherLineClass = classNode("Other").at(endColumn = 6)
        val multilineCall = ConstructorCallExpression(otherLineClass, ArgumentListExpression())
            .at(line = 2, endLine = 3, endColumn = 20)
        assertSame(otherLineClass, queryFor(otherLineClass, multilineCall).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `method name filtering uses identity and preserves unrelated equal literals`() {
        val name = ConstantExpression("run").at(endColumn = 6)
        val equalLiteral = ConstantExpression("run").at(endColumn = 6)
        val call = MethodCallExpression(VariableExpression("receiver"), name, ArgumentListExpression())
            .at(endColumn = 20)

        assertSame(call, queryFor(call, name).getNodeAt(uri, 2, 5))
        assertSame(equalLiteral, queryFor(call, name, equalLiteral).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `property name filtering uses identity and preserves unrelated equal literals`() {
        val name = ConstantExpression("value").at(endColumn = 6)
        val equalLiteral = ConstantExpression("value").at(endColumn = 6)
        val property = PropertyExpression(VariableExpression("receiver"), name).at(endColumn = 20)

        assertSame(property, queryFor(property, name).getNodeAt(uri, 2, 5))
        assertSame(equalLiteral, queryFor(property, name, equalLiteral).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `smaller variable arguments take precedence over their enclosing call`() {
        val argument = VariableExpression("argument").at(endColumn = 6)
        val call = MethodCallExpression(VariableExpression("receiver"), "run", ArgumentListExpression(argument))
            .at(endColumn = 20)

        assertSame(argument, queryFor(call, argument).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `equally sized nodes prefer actionable calls then identifiers then literals then generic expressions`() {
        val call = MethodCallExpression(VariableExpression("receiver"), "run", ArgumentListExpression()).at()
        val variable = VariableExpression("value").at()
        val literal = ConstantExpression("literal").at()
        val arguments = ArgumentListExpression().at()

        assertSame(call, queryFor(variable, call).getNodeAt(uri, 2, 5))
        assertSame(variable, queryFor(literal, variable).getNodeAt(uri, 2, 5))
        assertSame(literal, queryFor(arguments, literal).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `equally sized declarations prefer fields then methods then classes then other nodes`() {
        val type = classNode("Widget").at()
        val method = MethodNode("run", 0, ClassHelper.OBJECT_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null)
            .at()
        val field = FieldNode("value", 0, ClassHelper.OBJECT_TYPE, type, null).at()
        val other = ASTNode().at()

        assertSame(field, queryFor(method, field).getNodeAt(uri, 2, 5))
        assertSame(method, queryFor(type, method).getNodeAt(uri, 2, 5))
        assertSame(type, queryFor(other, type).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `missing ends use token lengths for containment and size ranking`() {
        val variable = VariableExpression("token").at(endLine = -1, endColumn = -1)
        val wider = ConstantExpression("literal").at(endColumn = 10)
        val query = queryFor(wider, variable)

        assertSame(variable, query.getNodeAt(uri, 2, 8))
        assertSame(wider, query.getNodeAt(uri, 2, 9))
        assertNull(queryFor(variable).getNodeAt(uri, 3, 4))
    }

    @Test
    fun `missing ends without token hints cover one extra column and retain smaller size priority`() {
        val literal = ConstantExpression("literal").at(endLine = -1, endColumn = -1)
        val variable = VariableExpression("variable").at(endColumn = 7)
        val query = queryFor(literal)

        assertSame(literal, query.getNodeAt(uri, 2, 4))
        assertSame(literal, query.getNodeAt(uri, 2, 5))
        assertNull(query.getNodeAt(uri, 2, 6))
        assertSame(literal, queryFor(variable, literal).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `implicit receivers do not widen missing end coordinates to their name length`() {
        listOf("this", "super").forEach { name ->
            val receiver = VariableExpression(name).at(endLine = -1, endColumn = -1)
            val query = queryFor(receiver)

            assertSame(receiver, query.getNodeAt(uri, 2, 5))
            assertNull(query.getNodeAt(uri, 2, 6))
        }
    }

    @Test
    fun `single line nodes beat multiline nodes and multiline spans use long arithmetic`() {
        val singleLine = ASTNode().at(endColumn = 20)
        val multiline = VariableExpression("value").at(endLine = 4)
        assertSame(singleLine, queryFor(multiline, singleLine).getNodeAt(uri, 2, 5))

        val shorter = ASTNode().at(endLine = 2_000_000)
        val longer = VariableExpression("value").at(endLine = 3_000_000)
        assertSame(shorter, queryFor(longer, shorter).getNodeAt(uri, 2, 5))
    }

    @Test
    fun `equal size and priority retain the first node in the tracker snapshot`() {
        val tracker = NodeRelationshipTracker()
        listOf(ConstantExpression("first").at(), ConstantExpression("second").at()).forEach { node ->
            tracker.pushNode(node, uri)
            tracker.popNode()
        }
        val expected = tracker.getNodes(uri).first()

        assertSame(expected, AstPositionQuery(tracker).getNodeAt(uri, 2, 5))
    }

    private fun queryFor(vararg nodes: ASTNode): AstPositionQuery {
        val tracker = NodeRelationshipTracker()
        nodes.forEach { node ->
            tracker.pushNode(node, uri)
            tracker.popNode()
        }
        return AstPositionQuery(tracker)
    }

    private fun classNode(name: String): ClassNode = ClassNode(name, 0, ClassHelper.OBJECT_TYPE)

    private fun <T : ASTNode> T.at(line: Int = 3, column: Int = 5, endLine: Int = line, endColumn: Int = 8): T = apply {
        lineNumber = line
        columnNumber = column
        lastLineNumber = endLine
        lastColumnNumber = endColumn
    }
}
