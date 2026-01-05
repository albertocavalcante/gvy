package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Direct attribute/field access expression: `obj.@field`
 * Bypasses getters to access the field directly.
 *
 * Example:
 * ```groovy
 * class Person {
 *     String name
 *     String getName() { "Mr. $name" }
 * }
 * person.@name    // Direct field access, returns raw value
 * person.name     // Property access, returns "Mr. ..."
 * ```
 */
class AttributeExpr(val objectExpression: Expression, val attribute: String) : Expression() {

    init {
        setAsParentNodeOf(objectExpression)
    }

    override fun getChildNodes(): List<Node> = listOf(objectExpression)

    override fun toString(): String = "AttributeExpr[@$attribute]"
}
