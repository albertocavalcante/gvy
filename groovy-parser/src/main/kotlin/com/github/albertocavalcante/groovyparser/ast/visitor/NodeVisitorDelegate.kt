package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
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
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import java.net.URI

/**
 * Delegate class that handles all actual node visiting for AstVisitor.
 * This separation reduces the AstVisitor function count from 37 to 10.
 *
 * TODO: This class has 29 functions, which naturally occurs in visitor pattern implementations.
 * We've excluded visitor pattern classes from detekt TooManyFunctions rule because each AST
 * node type requires its own visit method. This is a legitimate architectural pattern.
 *
 * The visitor pattern could be further optimized with:
 * - Method dispatch tables instead of individual methods
 * - Reflection-based visiting
 * - Split into specialized visitors (expressions, statements, declarations)
 */
internal class NodeVisitorDelegate(private val tracker: NodeRelationshipTracker) : ClassCodeVisitorSupport() {

    private var _sourceUnit: SourceUnit? = null
    private var currentUri: URI? = null

    public override fun getSourceUnit(): SourceUnit? = _sourceUnit

    fun visitModule(module: ModuleNode, sourceUnit: SourceUnit, uri: URI) {
        this._sourceUnit = sourceUnit
        this.currentUri = uri
        tracker.clear()
        processModule(module)
    }

    private fun processModule(module: ModuleNode) {
        track(module) {
            // Visit all imports in the module
            module.imports?.forEach { importNode ->
                track(importNode) {}
            }

            // Visit star imports
            module.starImports?.forEach { importNode ->
                track(importNode) {}
            }

            // Visit static imports
            module.staticImports?.values?.forEach { importNode ->
                track(importNode) {}
            }

            // Visit static star imports
            module.staticStarImports?.values?.forEach { importNode ->
                track(importNode) {}
            }

            // Visit all classes in the module
            module.classes.forEach { classNode ->
                visitClass(classNode)
            }

            // Also visit any script body code if this is a script
            if (module.statementBlock != null) {
                visitBlockStatement(module.statementBlock)
            }
        }
    }

    /**
     * Track a node during visitation.
     *
     * Refactored from explicit pushNode/popNode calls to reduce boilerplate.
     * Maintains legacy behavior (unbalanced push/pop for synthetic nodes) to ensure
     * parity with existing tests.
     */
    private fun track(node: ASTNode, block: () -> Unit) {
        val shouldTrack = node.lineNumber > 0 && node.columnNumber > 0
        if (shouldTrack) {
            tracker.pushNode(node, currentUri)
        }
        try {
            block()
        } finally {
            tracker.popNode()
        }
    }

    /**
     * Visit annotations on an annotated node
     */
    override fun visitAnnotations(node: AnnotatedNode) {
        node.annotations?.forEach { annotation ->
            track(annotation) {
                processAnnotationMembers(annotation)
            }
        }
    }

    /**
     * Process annotation members to visit their expressions.
     */
    private fun processAnnotationMembers(annotation: org.codehaus.groovy.ast.AnnotationNode) {
        annotation.members?.forEach { (_, value) ->
            if (value is org.codehaus.groovy.ast.expr.Expression) {
                value.visit(this)
            }
        }
    }

    // Override visitor methods to track nodes

    override fun visitClass(node: ClassNode) {
        track(node) { super.visitClass(node) }
    }

    override fun visitMethod(node: MethodNode) {
        track(node) {
            // Visit parameters
            node.parameters?.forEach { param ->
                track(param) {}
            }
            super.visitMethod(node)
        }
    }

    override fun visitField(node: FieldNode) {
        track(node) { super.visitField(node) }
    }

    override fun visitProperty(node: PropertyNode) {
        track(node) { super.visitProperty(node) }
    }

    // Expression visitors

    override fun visitMethodCallExpression(call: MethodCallExpression) {
        track(call) {
            // Manually visit arguments to ensure they are tracked, as standard visitor support seems flaky for ArgumentList
            val args = call.arguments
            if (args is org.codehaus.groovy.ast.expr.TupleExpression) {
                args.expressions?.forEach { it.visit(this) }
            } else {
                args?.visit(this)
            }

            // Manually visit the method expression (object and method name) instead of calling super
            // to avoid potential duplicate visitation of arguments if super were to visit them.
            call.objectExpression?.visit(this)
            call.method?.visit(this)
        }
    }

    override fun visitVariableExpression(expression: VariableExpression) {
        track(expression) { super.visitVariableExpression(expression) }
    }

    override fun visitDeclarationExpression(expression: DeclarationExpression) {
        track(expression) {
            expression.leftExpression.visit(this)
            expression.rightExpression.visit(this)
        }
    }

    override fun visitBinaryExpression(expression: BinaryExpression) {
        track(expression) { super.visitBinaryExpression(expression) }
    }

    override fun visitPropertyExpression(expression: PropertyExpression) {
        track(expression) { super.visitPropertyExpression(expression) }
    }

    override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
        track(call) { super.visitConstructorCallExpression(call) }
    }

    override fun visitTupleExpression(expression: org.codehaus.groovy.ast.expr.TupleExpression) {
        track(expression) { super.visitTupleExpression(expression) }
    }

    override fun visitClassExpression(expression: ClassExpression) {
        track(expression) { super.visitClassExpression(expression) }
    }

    override fun visitClosureExpression(expression: ClosureExpression) {
        track(expression) {
            // Visit closure parameters
            expression.parameters?.forEach { param ->
                track(param) {}
            }
            super.visitClosureExpression(expression)
        }
    }

    override fun visitGStringExpression(expression: org.codehaus.groovy.ast.expr.GStringExpression) {
        track(expression) { super.visitGStringExpression(expression) }
    }

    override fun visitConstantExpression(expression: org.codehaus.groovy.ast.expr.ConstantExpression) {
        track(expression) { super.visitConstantExpression(expression) }
    }

    override fun visitListExpression(expression: ListExpression) {
        track(expression) { super.visitListExpression(expression) }
    }

    override fun visitMapExpression(expression: MapExpression) {
        track(expression) { super.visitMapExpression(expression) }
    }

    override fun visitRangeExpression(expression: RangeExpression) {
        track(expression) { super.visitRangeExpression(expression) }
    }

    override fun visitTernaryExpression(expression: TernaryExpression) {
        track(expression) { super.visitTernaryExpression(expression) }
    }

    override fun visitSpreadExpression(expression: SpreadExpression) {
        track(expression) { super.visitSpreadExpression(expression) }
    }

    override fun visitSpreadMapExpression(expression: SpreadMapExpression) {
        track(expression) { super.visitSpreadMapExpression(expression) }
    }

    // Statement visitors

    override fun visitBlockStatement(block: BlockStatement) {
        track(block) { super.visitBlockStatement(block) }
    }

    override fun visitExpressionStatement(statement: ExpressionStatement) {
        track(statement) { super.visitExpressionStatement(statement) }
    }

    // Additional statement visitors for complete coverage

    override fun visitForLoop(loop: org.codehaus.groovy.ast.stmt.ForStatement) {
        track(loop) { super.visitForLoop(loop) }
    }

    override fun visitWhileLoop(loop: org.codehaus.groovy.ast.stmt.WhileStatement) {
        track(loop) { super.visitWhileLoop(loop) }
    }

    /**
     * Override visitDoWhileLoop to ensure DoWhileStatement nodes are tracked.
     */
    override fun visitDoWhileLoop(loop: org.codehaus.groovy.ast.stmt.DoWhileStatement) {
        track(loop) { super.visitDoWhileLoop(loop) }
    }

    override fun visitIfElse(ifElse: org.codehaus.groovy.ast.stmt.IfStatement) {
        track(ifElse) { super.visitIfElse(ifElse) }
    }

    /**
     * Override visitTryCatchFinally to ensure TryCatchStatement and CatchStatement nodes are tracked.
     *
     * Note: CatchStatement doesn't have its own visitor method in ClassCodeVisitorSupport,
     * so we manually track each catch statement along with its parameter and code block.
     */
    override fun visitTryCatchFinally(statement: org.codehaus.groovy.ast.stmt.TryCatchStatement) {
        track(statement) {
            // Manually visit catch statements to ensure they are tracked
            statement.catchStatements?.forEach { catchStmt ->
                track(catchStmt) {
                    // Visit the catch parameter
                    val param = catchStmt.variable
                    if (param != null) {
                        track(param) {}
                    }
                    // Visit the catch block
                    catchStmt.code?.visit(this)
                }
            }

            // Visit try block
            statement.tryStatement?.visit(this)

            // Visit finally block
            statement.finallyStatement?.visit(this)
        }
    }

    override fun visitReturnStatement(statement: org.codehaus.groovy.ast.stmt.ReturnStatement) {
        track(statement) { super.visitReturnStatement(statement) }
    }

    override fun visitThrowStatement(statement: org.codehaus.groovy.ast.stmt.ThrowStatement) {
        track(statement) { super.visitThrowStatement(statement) }
    }

    /**
     * Override visitSwitch to ensure SwitchStatement and all CaseStatement nodes are tracked.
     *
     * We manually visit each case statement to ensure comprehensive tracking of all switch cases,
     * including the default case. This ensures that case statements are hoverable and discoverable.
     */
    override fun visitSwitch(statement: org.codehaus.groovy.ast.stmt.SwitchStatement) {
        track(statement) {
            // Visit the expression being switched on
            statement.expression?.visit(this)

            // Manually visit case statements to ensure they are all tracked
            statement.caseStatements?.forEach { caseStmt ->
                visitCaseStatement(caseStmt)
            }

            // Visit the default statement if it exists
            statement.defaultStatement?.visit(this)
        }
    }

    /**
     * Override visitCaseStatement to ensure CaseStatement nodes are tracked.
     */
    override fun visitCaseStatement(statement: org.codehaus.groovy.ast.stmt.CaseStatement) {
        track(statement) { super.visitCaseStatement(statement) }
    }

    /**
     * Override visitBreakStatement to ensure BreakStatement nodes are tracked.
     */
    override fun visitBreakStatement(statement: org.codehaus.groovy.ast.stmt.BreakStatement) {
        track(statement) { super.visitBreakStatement(statement) }
    }

    /**
     * Override visitContinueStatement to ensure ContinueStatement nodes are tracked.
     */
    override fun visitContinueStatement(statement: org.codehaus.groovy.ast.stmt.ContinueStatement) {
        track(statement) { super.visitContinueStatement(statement) }
    }
}
