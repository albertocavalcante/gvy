package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.Parameter

/**
 * Represents a try-catch-finally statement:
 * ```groovy
 * try {
 *     // try block
 * } catch (ExceptionType e) {
 *     // catch block
 * } finally {
 *     // finally block
 * }
 * ```
 */
class TryCatchStatement(val tryBlock: Statement) : Statement() {

    /** List of catch clauses */
    val catchClauses: MutableList<CatchClause> = mutableListOf()

    /** Optional finally block */
    private var _finallyBlock: Statement? = null
    var finallyBlock: Statement?
        get() = _finallyBlock
        set(value) {
            _finallyBlock = value
            value?.let { setAsParentNodeOf(it) }
        }

    init {
        setAsParentNodeOf(tryBlock)
    }

    fun addCatchClause(catchClause: CatchClause) {
        catchClauses.add(catchClause)
        setAsParentNodeOf(catchClause)
    }

    override fun getChildNodes(): List<Node> = buildList {
        add(tryBlock)
        addAll(catchClauses)
        finallyBlock?.let { add(it) }
    }

    override fun toString(): String = "TryCatchStatement[${catchClauses.size} catch(es)]"
}

/**
 * Represents a catch clause in a try-catch statement.
 */
class CatchClause(val parameter: Parameter, val body: Statement) : Node() {

    init {
        setAsParentNodeOf(parameter)
        setAsParentNodeOf(body)
    }

    override fun getChildNodes(): List<Node> = listOf(parameter, body)

    override fun toString(): String = "CatchClause[${parameter.type}]"
}
