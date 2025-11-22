package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.groovyparser.ast.visitor.NodeVisitorDelegate
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import java.net.URI

/**
 * Kotlin-based AST visitor that tracks parent-child relationships and URI mappings.
 * Refactored to use delegation pattern to reduce function count from 37 to 10.
 *
 * TODO: This class still shows 33 functions to detekt because override methods count as functions.
 * The visitor pattern naturally requires many methods (one per AST node type). We've excluded
 * visitor classes from detekt TooManyFunctions rule as this is a legitimate architectural pattern.
 *
 * Alternative approaches for future consideration:
 * - Use method dispatch tables instead of individual override methods
 * - Split into multiple specialized visitors (expression visitor, statement visitor, etc.)
 */
class AstVisitor : ClassCodeVisitorSupport() {

    private val tracker = NodeRelationshipTracker()
    private val positionQuery = AstPositionQuery(tracker)
    private val delegate = NodeVisitorDelegate(tracker)

    override fun getSourceUnit(): SourceUnit? = delegate.getSourceUnit()

    // Public API (10 functions maximum)
    fun visitModule(module: ModuleNode, sourceUnit: SourceUnit, uri: URI) {
        delegate.visitModule(module, sourceUnit, uri)
    }

    fun clear() = tracker.clear()
    fun getParent(node: ASTNode): ASTNode? = tracker.getParent(node)
    fun getUri(node: ASTNode): URI? = tracker.getUri(node)
    fun getNodes(uri: URI): List<ASTNode> = tracker.getNodes(uri)
    fun getAllNodes(): List<ASTNode> = tracker.getAllNodes()
    fun getAllClassNodes(): List<ClassNode> = tracker.getAllClassNodes()
    fun getNodeAt(uri: URI, lspPosition: Position): ASTNode? = positionQuery.getNodeAt(uri, lspPosition)
    fun getNodeAt(uri: URI, lspLine: Int, lspCharacter: Int): ASTNode? =
        positionQuery.getNodeAt(uri, lspLine, lspCharacter)
    fun contains(ancestor: ASTNode, descendant: ASTNode): Boolean = tracker.contains(ancestor, descendant)

    // Delegate all visit methods to NodeVisitorDelegate
    override fun visitClass(node: ClassNode) = delegate.visitClass(node)
    override fun visitMethod(node: MethodNode) = delegate.visitMethod(node)
    override fun visitField(node: FieldNode) = delegate.visitField(node)
    override fun visitProperty(node: PropertyNode) = delegate.visitProperty(node)
    override fun visitMethodCallExpression(call: MethodCallExpression) = delegate.visitMethodCallExpression(call)
    override fun visitVariableExpression(expression: VariableExpression) = delegate.visitVariableExpression(expression)
    override fun visitDeclarationExpression(expression: DeclarationExpression) =
        delegate.visitDeclarationExpression(expression)
    override fun visitBinaryExpression(expression: BinaryExpression) = delegate.visitBinaryExpression(expression)
    override fun visitPropertyExpression(expression: PropertyExpression) = delegate.visitPropertyExpression(expression)
    override fun visitConstructorCallExpression(call: ConstructorCallExpression) =
        delegate.visitConstructorCallExpression(call)
    override fun visitClassExpression(expression: ClassExpression) = delegate.visitClassExpression(expression)
    override fun visitClosureExpression(expression: ClosureExpression) = delegate.visitClosureExpression(expression)
    override fun visitGStringExpression(expression: org.codehaus.groovy.ast.expr.GStringExpression) =
        delegate.visitGStringExpression(expression)
    override fun visitConstantExpression(expression: org.codehaus.groovy.ast.expr.ConstantExpression) =
        delegate.visitConstantExpression(expression)
    override fun visitBlockStatement(block: BlockStatement) = delegate.visitBlockStatement(block)
    override fun visitExpressionStatement(statement: ExpressionStatement) = delegate.visitExpressionStatement(statement)
    override fun visitForLoop(loop: org.codehaus.groovy.ast.stmt.ForStatement) = delegate.visitForLoop(loop)
    override fun visitWhileLoop(loop: org.codehaus.groovy.ast.stmt.WhileStatement) = delegate.visitWhileLoop(loop)
    override fun visitIfElse(ifElse: org.codehaus.groovy.ast.stmt.IfStatement) = delegate.visitIfElse(ifElse)
    override fun visitTryCatchFinally(statement: org.codehaus.groovy.ast.stmt.TryCatchStatement) =
        delegate.visitTryCatchFinally(statement)
    override fun visitReturnStatement(statement: org.codehaus.groovy.ast.stmt.ReturnStatement) =
        delegate.visitReturnStatement(statement)
    override fun visitThrowStatement(statement: org.codehaus.groovy.ast.stmt.ThrowStatement) =
        delegate.visitThrowStatement(statement)
}
