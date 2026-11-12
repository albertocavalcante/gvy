// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport

/** Uses Groovy's own overload rules, retaining the source declaration behind omitted default parameters. */
internal object HoverMethodSelection {
    fun select(receiver: ClassNode, methods: List<MethodNode>, arguments: List<Expression>): List<MethodNode> {
        // Dynamic and spread arguments cannot establish a unique target from their static AST types.
        if (arguments.any { it is SpreadExpression || ClassHelper.isDynamicTyped(it.type) }) return emptyList()
        val variants = methods.flatMap { original ->
            parameterVariants(original.parameters).map { parameters ->
                val method = MethodNode(
                    original.name,
                    original.modifiers,
                    original.returnType,
                    parameters,
                    original.exceptions,
                    original.code,
                ).apply {
                    declaringClass = original.declaringClass
                    genericsTypes = original.genericsTypes
                }
                method to original
            }
        }

        @Suppress("SpreadOperator") // Groovy's compiler selector exposes only a Java varargs API.
        val selected = StaticTypeCheckingSupport.chooseBestMethod(
            receiver,
            variants.map { it.first },
            *arguments.map { it.type }.toTypedArray(),
        )
        return selected.mapNotNull { method -> variants.find { it.first === method }?.second }.distinct()
    }

    private fun parameterVariants(parameters: Array<Parameter>): List<Array<Parameter>> {
        val variants = mutableListOf(parameters)
        var remaining = parameters.toList()
        while (true) {
            val optional = remaining.indexOfLast { it.hasInitialExpression() }
            if (optional < 0) return variants
            remaining = remaining.filterIndexed { index, _ -> index != optional }
            variants.add(remaining.toTypedArray())
        }
    }
}
