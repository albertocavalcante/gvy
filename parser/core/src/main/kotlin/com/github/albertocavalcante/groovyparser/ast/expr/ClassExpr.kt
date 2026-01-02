package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Class expression: `String` or `String.class`
 * Represents a reference to a class/type.
 *
 * Example:
 * ```groovy
 * def clazz = String
 * def clazz2 = String.class
 * obj instanceof String
 * ```
 */
class ClassExpr(val className: String) : Expression() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = "ClassExpr[$className]"
}
