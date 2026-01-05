package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a block of statements: `{ stmt1; stmt2; ... }`
 */
class BlockStatement : Statement() {

    /** The statements in this block */
    private val stmts: MutableList<Statement> = mutableListOf()

    /** Returns the statements in this block */
    val statements: List<Statement>
        get() = stmts.toList()

    /**
     * Adds a statement to this block.
     */
    fun addStatement(statement: Statement) {
        stmts.add(statement)
        setAsParentNodeOf(statement)
    }

    override fun getChildNodes(): List<Node> = stmts.toList()

    override fun toString(): String = "BlockStatement[${stmts.size} statement(s)]"
}
