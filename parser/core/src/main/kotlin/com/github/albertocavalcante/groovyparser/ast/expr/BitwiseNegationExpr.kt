// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Bitwise negation expression: `~expr`
 *
 * Example:
 * ```groovy
 * def inverted = ~0xFF
 * def pattern = ~"foo.*"  // Also used for regex pattern
 * ```
 */
class BitwiseNegationExpr(val expression: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "BitwiseNegationExpr"
}
