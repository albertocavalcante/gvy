// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/*
 * INTERNAL TO GVY - NOT PUBLIC API. See ClassHierarchySafety.kt.
 */

package com.github.albertocavalcante.groovyparser.api.internal.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression

/**
 * Whether [node] is a class literal or constructor call (`T.class`, `T.foo()`, `new T()`, the right side of
 * `x instanceof T`) whose type is a type parameter. The compiler gives such a type a placeholder that redirects to the
 * parameter's bound, which may be a class spelled the same, but the use is the parameter's, never the bound class's:
 * the one rule every consumer of "a use of class C" (the reference scan, the semantic index) shares.
 */
fun isTypedByTypeParameter(node: ASTNode): Boolean = when (node) {
    is ClassExpression -> node.type.isGenericsPlaceHolder
    is ConstructorCallExpression -> node.type.isGenericsPlaceHolder
    else -> false
}
