package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a field access expression:
 * ```groovy
 * this.@field   // Direct field access bypassing getters
 * ```
 *
 * Note: This is different from PropertyExpr which goes through getters/setters.
 */
class FieldExpr(val scope: Expression, val fieldName: String) : Expression() {

    init {
        setAsParentNodeOf(scope)
    }

    override fun getChildNodes(): List<Node> = listOf(scope)

    override fun toString(): String = "FieldExpr[$fieldName]"
}

/**
 * Represents a static method call:
 * ```groovy
 * Math.max(1, 2)
 * Integer.parseInt("42")
 * ```
 *
 * This is used when the receiver is known to be a class at parse time.
 */
class StaticMethodCallExpr(val ownerType: String, val methodName: String) : Expression() {

    val arguments: MutableList<Expression> = mutableListOf()

    fun addArgument(arg: Expression) {
        arguments.add(arg)
        setAsParentNodeOf(arg)
    }

    override fun getChildNodes(): List<Node> = arguments.toList()

    override fun toString(): String = "StaticMethodCallExpr[$ownerType.$methodName]"
}

/**
 * Represents a tuple expression (multiple assignment):
 * ```groovy
 * def (a, b, c) = [1, 2, 3]
 * (x, y) = [y, x]  // swap
 * ```
 */
class TupleExpr : Expression() {

    val elements: MutableList<Expression> = mutableListOf()

    fun addElement(element: Expression) {
        elements.add(element)
        setAsParentNodeOf(element)
    }

    override fun getChildNodes(): List<Node> = elements.toList()

    override fun toString(): String = "TupleExpr[${elements.size} elements]"
}

/**
 * Represents a boolean expression wrapper.
 *
 * In Groovy's AST, BooleanExpression wraps another expression to indicate
 * it's used in a boolean context (conditions, loops, etc.).
 */
class BooleanExpr(val expression: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "BooleanExpr[$expression]"
}

/**
 * Represents a closure list expression used in for loops:
 * ```groovy
 * for (i = 0, j = 10; i < j; i++, j--) { }
 * ```
 *
 * The expressions in the for-loop header (init, condition, update) are
 * represented as ClosureListExpression in Groovy's AST.
 */
class ClosureListExpr : Expression() {

    val expressions: MutableList<Expression> = mutableListOf()

    fun addExpression(expr: Expression) {
        expressions.add(expr)
        setAsParentNodeOf(expr)
    }

    override fun getChildNodes(): List<Node> = expressions.toList()

    override fun toString(): String = "ClosureListExpr[${expressions.size} expressions]"
}

/**
 * Represents an empty expression (no-op).
 *
 * Used as a placeholder when an expression is syntactically required
 * but nothing is present.
 */
object EmptyExpr : Expression() {
    override fun getChildNodes(): List<Node> = emptyList()
    override fun toString(): String = "EmptyExpr"
}

/**
 * Represents named arguments in a method call:
 * ```groovy
 * method(name: "John", age: 30)
 * ```
 */
class NamedArgumentListExpr : Expression() {

    val arguments: MutableList<MapEntryExpr> = mutableListOf()

    fun addArgument(entry: MapEntryExpr) {
        arguments.add(entry)
        setAsParentNodeOf(entry)
    }

    override fun getChildNodes(): List<Node> = arguments.toList()

    override fun toString(): String = "NamedArgumentListExpr[${arguments.size} args]"
}

/**
 * Represents an argument list in a method call:
 * ```groovy
 * method(arg1, arg2, arg3)
 * ```
 */
class ArgumentListExpr : Expression() {

    val arguments: MutableList<Expression> = mutableListOf()

    fun addArgument(arg: Expression) {
        arguments.add(arg)
        setAsParentNodeOf(arg)
    }

    override fun getChildNodes(): List<Node> = arguments.toList()

    override fun toString(): String = "ArgumentListExpr[${arguments.size} args]"
}
