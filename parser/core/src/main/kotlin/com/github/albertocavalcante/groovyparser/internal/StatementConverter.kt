package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BreakStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
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
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.AssertStatement as GroovyAssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement as GroovyBlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement as GroovyBreakStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement as GroovyContinueStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement as GroovyExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement as GroovyForStatement
import org.codehaus.groovy.ast.stmt.IfStatement as GroovyIfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement as GroovyReturnStatement
import org.codehaus.groovy.ast.stmt.Statement as GroovyStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement as GroovySwitchStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement as GroovyThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement as GroovyTryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement as GroovyWhileStatement

/**
 * Converts control flow statements (if, for, while, try, switch, etc.).
 *
 * Handles ~280 lines of statement conversion logic.
 */
internal class StatementConverter(private val setRange: (Node, ASTNode) -> Unit) {

    /**
     * Main statement dispatcher.
     */
    fun convert(
        stmt: GroovyStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): Statement? = when (stmt) {
        is GroovyBlockStatement -> convertBlock(stmt, convertExpr)
        is GroovyExpressionStatement -> convertExpressionStatement(stmt, convertExpr)
        is GroovyIfStatement -> convertIf(stmt, convertExpr)
        is GroovyTryCatchStatement -> convertTryCatch(stmt, convertExpr)
        is GroovySwitchStatement -> convertSwitch(stmt, convertExpr)
        is GroovyAssertStatement -> convertAssert(stmt, convertExpr)
        is EmptyStatement -> null
        else -> convertLoopOrControl(stmt, convertExpr) ?: convertUnknown(stmt)
    }

    private fun convertLoopOrControl(
        stmt: GroovyStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): Statement? = when (stmt) {
        is GroovyReturnStatement -> convertReturn(stmt, convertExpr)
        is GroovyForStatement -> convertFor(stmt, convertExpr)
        is GroovyWhileStatement -> convertWhile(stmt, convertExpr)
        is GroovyThrowStatement -> convertThrow(stmt, convertExpr)
        is GroovyBreakStatement -> convertBreak(stmt)
        is GroovyContinueStatement -> convertContinue(stmt)
        else -> null
    }

    private fun convertUnknown(stmt: GroovyStatement): Statement {
        // For unknown statement types, wrap in a block if possible
        val block = BlockStatement()
        setRange(block, stmt)
        return block
    }

    /**
     * Converts a Groovy block statement (sequence of statements).
     */
    fun convertBlock(
        stmt: GroovyBlockStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): BlockStatement {
        val block = BlockStatement()
        stmt.statements?.forEach { s ->
            convert(s, convertExpr)?.let { block.addStatement(it) }
        }
        setRange(block, stmt)
        return block
    }

    /**
     * Converts a Groovy expression statement (expression used as a statement).
     */
    fun convertExpressionStatement(
        stmt: GroovyExpressionStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ExpressionStatement {
        val expr = convertExpr(stmt.expression)
        val exprStmt = ExpressionStatement(expr)
        setRange(exprStmt, stmt)
        return exprStmt
    }

    /**
     * Converts a Groovy return statement.
     */
    fun convertReturn(
        stmt: GroovyReturnStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ReturnStatement {
        val expr = stmt.expression?.let { convertExpr(it) }
        val returnStmt = ReturnStatement(expr)
        setRange(returnStmt, stmt)
        return returnStmt
    }

    /**
     * Converts a Groovy if statement.
     */
    fun convertIf(
        stmt: GroovyIfStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): IfStatement {
        val condition = convertExpr(stmt.booleanExpression.expression)
        val thenStmt = convert(stmt.ifBlock, convertExpr) ?: BlockStatement()
        val elseStmt = stmt.elseBlock?.let {
            if (it !is EmptyStatement) convert(it, convertExpr) else null
        }
        val ifStmt = IfStatement(condition, thenStmt, elseStmt)
        setRange(ifStmt, stmt)
        return ifStmt
    }

    /**
     * Converts a Groovy for statement (for-each loop).
     */
    fun convertFor(
        stmt: GroovyForStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ForStatement {
        val variableName = stmt.variable?.name ?: "it"
        val collection = convertExpr(stmt.collectionExpression)
        val body = convert(stmt.loopBlock, convertExpr) ?: BlockStatement()
        val forStmt = ForStatement(variableName, collection, body)
        setRange(forStmt, stmt)
        return forStmt
    }

    /**
     * Converts a Groovy while statement.
     */
    fun convertWhile(
        stmt: GroovyWhileStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): WhileStatement {
        val condition = convertExpr(stmt.booleanExpression.expression)
        val body = convert(stmt.loopBlock, convertExpr) ?: BlockStatement()
        val whileStmt = WhileStatement(condition, body)
        setRange(whileStmt, stmt)
        return whileStmt
    }

    /**
     * Converts a Groovy try-catch-finally statement.
     */
    fun convertTryCatch(
        stmt: GroovyTryCatchStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): TryCatchStatement {
        val tryBlock = convert(stmt.tryStatement, convertExpr) ?: BlockStatement()
        val tryCatch = TryCatchStatement(tryBlock)

        stmt.catchStatements?.forEach { catchStmt ->
            val param = Parameter(
                name = catchStmt.variable?.name ?: "e",
                type = catchStmt.exceptionType?.name ?: "Exception",
            )
            val body = convert(catchStmt.code, convertExpr) ?: BlockStatement()
            val catchClause = CatchClause(param, body)
            setRange(catchClause, catchStmt)
            tryCatch.addCatchClause(catchClause)
        }

        stmt.finallyStatement?.let { finallyStmt ->
            if (finallyStmt !is EmptyStatement) {
                tryCatch.finallyBlock = convert(finallyStmt, convertExpr)
            }
        }

        setRange(tryCatch, stmt)
        return tryCatch
    }

    /**
     * Converts a Groovy switch statement.
     */
    fun convertSwitch(
        stmt: GroovySwitchStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): SwitchStatement {
        val expression = convertExpr(stmt.expression)
        val switch = SwitchStatement(expression)

        stmt.caseStatements?.forEach { caseStmt ->
            val caseExpr = convertExpr(caseStmt.expression)
            val caseBody = convert(caseStmt.code, convertExpr) ?: BlockStatement()
            val case = CaseStatement(caseExpr, caseBody)
            setRange(case, caseStmt)
            switch.addCase(case)
        }

        stmt.defaultStatement?.let { defaultStmt ->
            if (defaultStmt !is EmptyStatement) {
                switch.defaultCase = convert(defaultStmt, convertExpr)
            }
        }

        setRange(switch, stmt)
        return switch
    }

    /**
     * Converts a Groovy throw statement.
     */
    fun convertThrow(
        stmt: GroovyThrowStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ThrowStatement {
        val expr = convertExpr(stmt.expression)
        val throwStmt = ThrowStatement(expr)
        setRange(throwStmt, stmt)
        return throwStmt
    }

    /**
     * Converts a Groovy assert statement.
     */
    fun convertAssert(
        stmt: GroovyAssertStatement,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): AssertStatement {
        val condition = convertExpr(stmt.booleanExpression.expression)
        val message = stmt.messageExpression?.let {
            if (it !is ConstantExpression || it.value != null) {
                convertExpr(it)
            } else {
                null
            }
        }
        val assertStmt = AssertStatement(condition, message)
        setRange(assertStmt, stmt)
        return assertStmt
    }

    /**
     * Converts a Groovy break statement.
     */
    fun convertBreak(stmt: GroovyBreakStatement): BreakStatement {
        val breakStmt = BreakStatement(stmt.label)
        setRange(breakStmt, stmt)
        return breakStmt
    }

    /**
     * Converts a Groovy continue statement.
     */
    fun convertContinue(stmt: GroovyContinueStatement): ContinueStatement {
        val continueStmt = ContinueStatement(stmt.label)
        setRange(continueStmt, stmt)
        return continueStmt
    }
}
