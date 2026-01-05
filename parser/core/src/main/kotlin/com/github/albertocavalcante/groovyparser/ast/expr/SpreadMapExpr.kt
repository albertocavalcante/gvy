package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Spread map operator expression: `*:map`
 * Spreads a map's entries as named arguments.
 *
 * Example:
 * ```groovy
 * def config = [host: 'localhost', port: 8080]
 * connect(*:config)  // Equivalent to connect(host: 'localhost', port: 8080)
 * ```
 */
class SpreadMapExpr(val expression: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "SpreadMapExpr"
}
