// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.Variable
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
            if (accessed !is VariableExpression) return resolveTerminal(current, accessed, context)
            val resolved = (context as? NativeTypeContext)?.lookupDeclaration(accessed)
            if (resolved != null) return resolved
            current = accessed
        }
        return SemanticType.Dynamic("cyclic variable binding: ${node.name}")
    }

    private fun resolveTerminal(node: VariableExpression, accessed: Variable?, context: TypeContext): SemanticType {
        if (accessed != null && accessed !is DynamicVariable) return context.calculateType(accessed)
        val fallback = if (accessed != null) {
            null
        } else if (context is NativeTypeContext) {
            context.lookupUnbound(node.name)
        } else {
            context.lookupSymbol(node.name)
        }
        return fallback ?: SemanticType.Dynamic("unresolved variable: ${node.name}")
    }
}
