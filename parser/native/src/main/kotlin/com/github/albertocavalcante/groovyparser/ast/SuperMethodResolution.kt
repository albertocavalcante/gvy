// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression

internal fun MethodCallExpression.isSuperCall(): Boolean {
    val receiver = objectExpression
    return receiver is VariableExpression && receiver.isSuperExpression
}

/**
 * `super.m()` reaches the nearest superclass declaring `m`, never the overriding method that contains
 * the call. Only declarations of this file are visible here; when the superclass lives elsewhere the
 * answer is null so cross-file resolution can take over (returning the override would be wrong).
 */
internal fun resolveSuperMethod(
    call: MethodCallExpression,
    candidates: List<MethodNode>,
    visitor: GroovyAstModel,
): MethodNode? {
    val enclosing = generateSequence(visitor.getParent(call)) { visitor.getParent(it) }
        .filterIsInstance<ClassNode>()
        .firstOrNull() ?: return null
    val arity = (call.arguments as? TupleExpression)?.expressions?.size
    // A cyclic hierarchy (`class A extends B`, `class B extends A`) ends the walk and never revisits `enclosing`.
    return enclosing.superClassChain()
        .firstNotNullOfOrNull { type ->
            candidates.filter { it.declaringClass?.name == type.name }.takeIf { it.isNotEmpty() }
        }
        ?.let { declared -> declared.firstOrNull { it.parameters.size == arity } ?: declared.first() }
}
