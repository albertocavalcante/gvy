package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

internal object ControlFlowCloner {

    private fun <T : Node> clone(node: T): T = NodeCloner.clone(node)

    fun cloneIfStatement(node: IfStatement): IfStatement {
        val cloned = IfStatement(
            condition = clone(node.condition),
            thenStatement = clone(node.thenStatement),
            elseStatement = node.elseStatement?.let { clone(it) },
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    fun cloneForStatement(node: ForStatement): ForStatement {
        val cloned = ForStatement(
            variableName = node.variableName,
            collectionExpression = clone(node.collectionExpression),
            body = clone(node.body),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    fun cloneWhileStatement(node: WhileStatement): WhileStatement {
        val cloned = WhileStatement(
            condition = clone(node.condition),
            body = clone(node.body),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    fun cloneTryCatchStatement(node: TryCatchStatement): TryCatchStatement {
        val cloned = TryCatchStatement(clone(node.tryBlock))
        node.catchClauses.forEach { cloned.catchClauses.add(cloneCatchClause(it)) }
        node.finallyBlock?.let { cloned.finallyBlock = clone(it) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    fun cloneCatchClause(node: CatchClause): CatchClause {
        val cloned = CatchClause(
            parameter = clone(node.parameter),
            body = clone(node.body),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    fun cloneSwitchStatement(node: SwitchStatement): SwitchStatement {
        val cloned = SwitchStatement(clone(node.expression))
        node.cases.forEach { cloned.cases.add(cloneCaseStatement(it)) }
        node.defaultCase?.let { cloned.defaultCase = clone(it) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    fun cloneCaseStatement(node: CaseStatement): CaseStatement {
        val cloned = CaseStatement(
            expression = clone(node.expression),
            body = clone(node.body),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }
}
