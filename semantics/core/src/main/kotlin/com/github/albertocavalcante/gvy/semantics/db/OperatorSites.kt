package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types
import java.util.IdentityHashMap

/**
 * Finds the syntax that calls a method without naming it (operators, `case`, `for (x in o)`, `as`, `++`), the
 * static calls Groovy rewrites into [StaticMethodCallExpression], and annotation members, and reports them to
 * [unresolved]. None of these is attributed to a declaration, so each is an uncertain use of its name.
 *
 * Increment and decrement targets are put in [assigned] (read and written), for the property accessors.
 */
internal class OperatorSites(
    private val unresolved: UnresolvedNames,
    private val assigned: IdentityHashMap<Expression, Boolean>,
) : ClassCodeVisitorSupport() {
    override fun getSourceUnit(): SourceUnit? = null

    override fun visitStaticMethodCallExpression(call: StaticMethodCallExpression) {
        // Groovy rewrites static-import and static-context implicit-this calls into this node. Its source
        // range is not just the name, so no occurrence is made; the name is recorded as unattributed.
        if (unresolved.isSource(call)) unresolved.callee(call.method)
        unresolved.addAll(DispatchNames.namedArguments(call.arguments))
        super.visitStaticMethodCallExpression(call)
    }

    override fun visitMethodCallExpression(call: MethodCallExpression) {
        // The call itself is the occurrence visitor's; its named arguments are the same on any call.
        unresolved.addAll(DispatchNames.namedArguments(call.arguments))
        super.visitMethodCallExpression(call)
    }

    override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
        // `new P(target: 'x')` sets the property `target` through the map constructor.
        unresolved.addAll(DispatchNames.namedArguments(call.arguments))
        super.visitConstructorCallExpression(call)
    }

    override fun visitConstructorOrMethod(node: MethodNode, isConstructor: Boolean) {
        if (generatedHelperMethod(node, unresolved.inTraitHelper)) return
        node.parameters?.filter { it.hasInitialExpression() }?.forEach { it.initialExpression.visit(this) }
        super.visitConstructorOrMethod(node, isConstructor)
    }

    override fun visitForLoop(forLoop: ForStatement) {
        // `for (x in o)` calls o.iterator(); a literal list or range, and the classic for(;;), do not.
        if (unresolved.isSource(forLoop) && !isLiteralIteration(forLoop.collectionExpression)) {
            unresolved.dispatch("iterator")
        }
        super.visitForLoop(forLoop)
    }

    private fun isLiteralIteration(collection: Expression) =
        collection is ListExpression || collection is RangeExpression || collection is ClosureListExpression

    override fun visitCaseStatement(statement: CaseStatement) {
        // `case k:` calls k.isCase(switchValue); a constant or a class has no user-defined isCase.
        if (unresolved.isSource(statement) && statement.expression !is ConstantExpression &&
            statement.expression !is ClassExpression
        ) {
            unresolved.dispatch("isCase")
        }
        super.visitCaseStatement(statement)
    }

    override fun visitCastExpression(expression: CastExpression) {
        if (expression.isCoerce && unresolved.isSource(expression)) unresolved.dispatch("asType")
        super.visitCastExpression(expression)
    }

    override fun visitUnaryMinusExpression(expression: UnaryMinusExpression) {
        unresolved.operator("negative", expression.expression)
        super.visitUnaryMinusExpression(expression)
    }

    override fun visitUnaryPlusExpression(expression: UnaryPlusExpression) {
        unresolved.operator("positive", expression.expression)
        super.visitUnaryPlusExpression(expression)
    }

    override fun visitBitwiseNegationExpression(expression: BitwiseNegationExpression) {
        unresolved.operator("bitwiseNegate", expression.expression)
        super.visitBitwiseNegationExpression(expression)
    }

    override fun visitPostfixExpression(expression: PostfixExpression) {
        increment(expression.operation.type, expression.expression)
        super.visitPostfixExpression(expression)
    }

    override fun visitPrefixExpression(expression: PrefixExpression) {
        increment(expression.operation.type, expression.expression)
        super.visitPrefixExpression(expression)
    }

    private fun increment(type: Int, operand: Expression) {
        assigned[operand] = true
        unresolved.operator(if (type == Types.PLUS_PLUS) "next" else "previous", operand)
    }

    override fun visitAnnotations(node: AnnotatedNode) {
        unresolved.annotations(node.annotations)
        super.visitAnnotations(node)
    }
}

/** A method the compiler added to a trait's helper class (`methodMissing`, `$init$`): it has no source position. */
internal fun generatedHelperMethod(node: MethodNode, inTraitHelper: Boolean) = inTraitHelper && node.lineNumber < 1
