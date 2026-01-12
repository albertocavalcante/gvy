package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BreakStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ContinueStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ThrowStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

internal object StatementCloner {

    @Suppress("CyclomaticComplexMethod", "UNCHECKED_CAST")
    fun <T : Statement> clone(node: T): T = when (node) {
        is BlockStatement -> cloneBlockStatement(node) as T
        is ExpressionStatement -> cloneExpressionStatement(node) as T
        is ReturnStatement -> cloneReturnStatement(node) as T
        is ThrowStatement -> cloneThrowStatement(node) as T
        is AssertStatement -> cloneAssertStatement(node) as T
        is BreakStatement -> cloneBreakStatement(node) as T
        is ContinueStatement -> cloneContinueStatement(node) as T

        is IfStatement -> ControlFlowCloner.cloneIfStatement(node) as T
        is ForStatement -> ControlFlowCloner.cloneForStatement(node) as T
        is WhileStatement -> ControlFlowCloner.cloneWhileStatement(node) as T
        is TryCatchStatement -> ControlFlowCloner.cloneTryCatchStatement(node) as T
        is SwitchStatement -> ControlFlowCloner.cloneSwitchStatement(node) as T
        is CaseStatement -> ControlFlowCloner.cloneCaseStatement(node) as T

        else -> throw UnsupportedOperationException("Cloning not supported for ${node::class.simpleName}")
    }

    private fun <T : Node> clone(node: T): T = NodeCloner.clone(node)

    private fun cloneBlockStatement(node: BlockStatement): BlockStatement {
        val cloned = BlockStatement()
        node.statements.forEach { cloned.addStatement(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneExpressionStatement(node: ExpressionStatement): ExpressionStatement {
        val cloned = ExpressionStatement(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneReturnStatement(node: ReturnStatement): ReturnStatement {
        val cloned = ReturnStatement(node.expression?.let { clone(it) })
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneThrowStatement(node: ThrowStatement): ThrowStatement {
        val cloned = ThrowStatement(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneAssertStatement(node: AssertStatement): AssertStatement {
        val cloned = AssertStatement(
            condition = clone(node.condition),
            message = node.message?.let { clone(it) },
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneBreakStatement(node: BreakStatement): BreakStatement {
        val cloned = BreakStatement(node.label)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneContinueStatement(node: ContinueStatement): ContinueStatement {
        val cloned = ContinueStatement(node.label)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }
}
