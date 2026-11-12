package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.expr.VariableExpression
import kotlin.reflect.KClass

/**
 * Calculator for VariableExpression nodes.
 * Uses scope lookup to resolve variable types.
 */
object VariableExpressionAdapter : TypeCalculator<VariableExpression> {

    override val nodeType: KClass<VariableExpression> = VariableExpression::class
    override val priority: Int = NativeCalculators.SCOPE_AWARE_PRIORITY // Higher than reflection-based fallback

    override fun calculate(node: VariableExpression, context: TypeContext): SemanticType? {
        val accessed = node.accessedVariable
        if (accessed is VariableExpression) {
            (context as? NativeTypeContext)?.lookupDeclaration(accessed)?.let { return it }
            if (accessed !== node) return context.calculateType(accessed)
        } else if (accessed != null && accessed !is DynamicVariable) {
            return context.calculateType(accessed)
        }

        // Unbound expressions may be supplied with an explicit scope by API callers.
        if (accessed == null) context.lookupSymbol(node.name)?.let { return it }
        return SemanticType.Dynamic("unresolved variable: ${node.name}")
    }
}
