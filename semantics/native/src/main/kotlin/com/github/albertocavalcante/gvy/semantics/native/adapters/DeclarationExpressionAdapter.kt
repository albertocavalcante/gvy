package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.expr.DeclarationExpression
import kotlin.reflect.KClass

/**
 * Calculator for DeclarationExpression nodes.
 * Infers type from RHS and registers in scope.
 */
object DeclarationExpressionAdapter : TypeCalculator<DeclarationExpression> {

    override val nodeType: KClass<DeclarationExpression> = DeclarationExpression::class
    override val priority: Int = NativeCalculators.SCOPE_AWARE_PRIORITY

    override fun calculate(node: DeclarationExpression, context: TypeContext): SemanticType? {
        val leftExpr = node.leftExpression
        val rightExpr = node.rightExpression

        // If explicitly typed, use that
        val declaredType = leftExpr.type
        if (declaredType != null && !declaredType.equals(ClassHelper.DYNAMIC_TYPE) &&
            !declaredType.equals(ClassHelper.OBJECT_TYPE)
        ) {
            return NativeTypeContext.fromClassNode(declaredType)
        }

        // Otherwise infer from RHS
        return context.calculateType(rightExpr)
    }
}
