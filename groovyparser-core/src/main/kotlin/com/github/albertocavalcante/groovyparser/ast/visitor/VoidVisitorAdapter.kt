package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TernaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BreakStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
import com.github.albertocavalcante.groovyparser.ast.stmt.ContinueStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ThrowStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

/**
 * A base implementation of [VoidVisitor] that traverses the entire AST.
 *
 * Override specific visit methods to perform operations on nodes of interest.
 * The default implementations recursively visit child nodes.
 *
 * Example usage:
 * ```kotlin
 * class MethodNameCollector : VoidVisitorAdapter<MutableList<String>>() {
 *     override fun visit(n: MethodDeclaration, arg: MutableList<String>) {
 *         arg.add(n.name)
 *         super.visit(n, arg) // Continue traversal
 *     }
 * }
 * ```
 *
 * @param A the type of the argument passed to visit methods
 */
open class VoidVisitorAdapter<A> : VoidVisitor<A> {

    override fun visit(n: CompilationUnit, arg: A) {
        n.packageDeclaration.ifPresent { visit(it, arg) }
        n.imports.forEach { visit(it, arg) }
        n.types.forEach { type ->
            if (type is ClassDeclaration) visit(type, arg)
        }
    }

    override fun visit(n: PackageDeclaration, arg: A) {
        n.annotations.forEach { visit(it, arg) }
    }

    override fun visit(n: ImportDeclaration, arg: A) {
        // Leaf node
    }

    override fun visit(n: AnnotationExpr, arg: A) {
        n.value?.let { visitExpression(it, arg) }
        n.members.values.forEach { visitExpression(it, arg) }
    }

    override fun visit(n: ClassDeclaration, arg: A) {
        n.annotations.forEach { visit(it, arg) }
        n.fields.forEach { visit(it, arg) }
        n.constructors.forEach { visit(it, arg) }
        n.methods.forEach { visit(it, arg) }
    }

    override fun visit(n: MethodDeclaration, arg: A) {
        n.annotations.forEach { visit(it, arg) }
        n.parameters.forEach { visit(it, arg) }
        n.body?.let { visitStatement(it, arg) }
    }

    override fun visit(n: FieldDeclaration, arg: A) {
        n.annotations.forEach { visit(it, arg) }
    }

    override fun visit(n: ConstructorDeclaration, arg: A) {
        n.annotations.forEach { visit(it, arg) }
        n.parameters.forEach { visit(it, arg) }
    }

    override fun visit(n: Parameter, arg: A) {
        n.annotations.forEach { visit(it, arg) }
    }

    // Statements

    override fun visit(n: BlockStatement, arg: A) {
        n.statements.forEach { visitStatement(it, arg) }
    }

    override fun visit(n: ExpressionStatement, arg: A) {
        visitExpression(n.expression, arg)
    }

    override fun visit(n: IfStatement, arg: A) {
        visitExpression(n.condition, arg)
        visitStatement(n.thenStatement, arg)
        n.elseStatement?.let { visitStatement(it, arg) }
    }

    override fun visit(n: ForStatement, arg: A) {
        visitExpression(n.collectionExpression, arg)
        visitStatement(n.body, arg)
    }

    override fun visit(n: WhileStatement, arg: A) {
        visitExpression(n.condition, arg)
        visitStatement(n.body, arg)
    }

    override fun visit(n: ReturnStatement, arg: A) {
        n.expression?.let { visitExpression(it, arg) }
    }

    override fun visit(n: TryCatchStatement, arg: A) {
        visitStatement(n.tryBlock, arg)
        n.catchClauses.forEach { visit(it, arg) }
        n.finallyBlock?.let { visitStatement(it, arg) }
    }

    override fun visit(n: CatchClause, arg: A) {
        visit(n.parameter, arg)
        visitStatement(n.body, arg)
    }

    override fun visit(n: SwitchStatement, arg: A) {
        visitExpression(n.expression, arg)
        n.cases.forEach { visit(it, arg) }
        n.defaultCase?.let { visitStatement(it, arg) }
    }

    override fun visit(n: CaseStatement, arg: A) {
        visitExpression(n.expression, arg)
        visitStatement(n.body, arg)
    }

    override fun visit(n: ThrowStatement, arg: A) {
        visitExpression(n.expression, arg)
    }

    override fun visit(n: AssertStatement, arg: A) {
        visitExpression(n.condition, arg)
        n.message?.let { visitExpression(it, arg) }
    }

    override fun visit(n: BreakStatement, arg: A) {
        // Leaf node
    }

    override fun visit(n: ContinueStatement, arg: A) {
        // Leaf node
    }

    // Expressions

    override fun visit(n: MethodCallExpr, arg: A) {
        n.objectExpression?.let { visitExpression(it, arg) }
        n.arguments.forEach { visitExpression(it, arg) }
    }

    override fun visit(n: VariableExpr, arg: A) {
        // Leaf node
    }

    override fun visit(n: ConstantExpr, arg: A) {
        // Leaf node
    }

    override fun visit(n: BinaryExpr, arg: A) {
        visitExpression(n.left, arg)
        visitExpression(n.right, arg)
    }

    override fun visit(n: PropertyExpr, arg: A) {
        visitExpression(n.objectExpression, arg)
    }

    override fun visit(n: ClosureExpr, arg: A) {
        n.parameters.forEach { visit(it, arg) }
        n.body?.let { visitStatement(it, arg) }
    }

    override fun visit(n: GStringExpr, arg: A) {
        n.expressions.forEach { visitExpression(it, arg) }
    }

    override fun visit(n: ListExpr, arg: A) {
        n.elements.forEach { visitExpression(it, arg) }
    }

    override fun visit(n: MapExpr, arg: A) {
        n.entries.forEach { visit(it, arg) }
    }

    override fun visit(n: MapEntryExpr, arg: A) {
        visitExpression(n.key, arg)
        visitExpression(n.value, arg)
    }

    override fun visit(n: RangeExpr, arg: A) {
        visitExpression(n.from, arg)
        visitExpression(n.to, arg)
    }

    override fun visit(n: TernaryExpr, arg: A) {
        visitExpression(n.condition, arg)
        visitExpression(n.trueExpression, arg)
        visitExpression(n.falseExpression, arg)
    }

    override fun visit(n: UnaryExpr, arg: A) {
        visitExpression(n.expression, arg)
    }

    override fun visit(n: CastExpr, arg: A) {
        visitExpression(n.expression, arg)
    }

    override fun visit(n: ConstructorCallExpr, arg: A) {
        n.arguments.forEach { visitExpression(it, arg) }
    }

    /**
     * Dispatches to the appropriate visit method for the statement type.
     */
    protected fun visitStatement(stmt: com.github.albertocavalcante.groovyparser.ast.stmt.Statement, arg: A) {
        when (stmt) {
            is BlockStatement -> visit(stmt, arg)
            is ExpressionStatement -> visit(stmt, arg)
            is IfStatement -> visit(stmt, arg)
            is ForStatement -> visit(stmt, arg)
            is WhileStatement -> visit(stmt, arg)
            is ReturnStatement -> visit(stmt, arg)
            is TryCatchStatement -> visit(stmt, arg)
            is SwitchStatement -> visit(stmt, arg)
            is CaseStatement -> visit(stmt, arg)
            is ThrowStatement -> visit(stmt, arg)
            is AssertStatement -> visit(stmt, arg)
            is BreakStatement -> visit(stmt, arg)
            is ContinueStatement -> visit(stmt, arg)
        }
    }

    /**
     * Dispatches to the appropriate visit method for the expression type.
     */
    protected fun visitExpression(expr: com.github.albertocavalcante.groovyparser.ast.expr.Expression, arg: A) {
        when (expr) {
            is MethodCallExpr -> visit(expr, arg)
            is VariableExpr -> visit(expr, arg)
            is ConstantExpr -> visit(expr, arg)
            is BinaryExpr -> visit(expr, arg)
            is PropertyExpr -> visit(expr, arg)
            is ClosureExpr -> visit(expr, arg)
            is GStringExpr -> visit(expr, arg)
            is ListExpr -> visit(expr, arg)
            is MapExpr -> visit(expr, arg)
            is MapEntryExpr -> visit(expr, arg)
            is RangeExpr -> visit(expr, arg)
            is TernaryExpr -> visit(expr, arg)
            is UnaryExpr -> visit(expr, arg)
            is CastExpr -> visit(expr, arg)
            is ConstructorCallExpr -> visit(expr, arg)
        }
    }
}
