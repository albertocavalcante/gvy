package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents a switch statement:
 * ```groovy
 * switch (expr) {
 *     case value1:
 *         // statements
 *         break
 *     case value2:
 *         // statements
 *         break
 *     default:
 *         // statements
 * }
 * ```
 */
class SwitchStatement(val expression: Expression) : Statement() {

    /** List of case clauses */
    val cases: MutableList<CaseStatement> = mutableListOf()

    /** Optional default case */
    private var _defaultCase: Statement? = null
    var defaultCase: Statement?
        get() = _defaultCase
        set(value) {
            _defaultCase = value
            value?.let { setAsParentNodeOf(it) }
        }

    init {
        setAsParentNodeOf(expression)
    }

    fun addCase(case: CaseStatement) {
        cases.add(case)
        setAsParentNodeOf(case)
    }

    override fun getChildNodes(): List<Node> = buildList {
        add(expression)
        addAll(cases)
        defaultCase?.let { add(it) }
    }

    override fun toString(): String = "SwitchStatement[${cases.size} case(s)]"
}

/**
 * Represents a case clause in a switch statement.
 */
class CaseStatement(val expression: Expression, val body: Statement) : Statement() {

    init {
        setAsParentNodeOf(expression)
        setAsParentNodeOf(body)
    }

    override fun getChildNodes(): List<Node> = listOf(expression, body)

    override fun toString(): String = "CaseStatement[$expression]"
}
