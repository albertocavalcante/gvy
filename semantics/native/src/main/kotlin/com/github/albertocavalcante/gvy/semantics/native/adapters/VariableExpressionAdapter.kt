package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.expr.VariableExpression
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * Calculator for VariableExpression nodes.
 * Uses scope lookup to resolve variable types.
 */
object VariableExpressionAdapter : TypeCalculator<VariableExpression> {

    override val nodeType: KClass<VariableExpression> = VariableExpression::class
    override val priority: Int = NativeCalculators.SCOPE_AWARE_PRIORITY // Higher than reflection-based fallback

    override fun calculate(node: VariableExpression, context: TypeContext): SemanticType? {
        val seen = Collections.newSetFromMap(IdentityHashMap<VariableExpression, Boolean>())
        var current = node
        while (seen.add(current)) {
            val accessed = current.accessedVariable
            if (accessed is VariableExpression) {
                (context as? NativeTypeContext)?.lookupDeclaration(accessed)?.let { return it }
                current = accessed
            } else if (accessed != null && accessed !is DynamicVariable) {
                return context.calculateType(accessed)
            } else {
                if (accessed == null) {
                    val fallback = if (context is NativeTypeContext) {
                        context.lookupUnbound(current.name)
                    } else {
                        context.lookupSymbol(current.name)
                    }
                    if (fallback != null) return fallback
                }
                return SemanticType.Dynamic("unresolved variable: ${current.name}")
            }
        }
        return SemanticType.Dynamic("cyclic variable binding: ${node.name}")
    }
}
