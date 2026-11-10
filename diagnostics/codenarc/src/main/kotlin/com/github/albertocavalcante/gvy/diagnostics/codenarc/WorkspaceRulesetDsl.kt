package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement

internal fun fail(message: String): Nothing = throw Rejection(message)

internal fun callOf(statement: Statement): MethodCallExpression? {
    val call = (statement as? ExpressionStatement)?.expression as? MethodCallExpression
    return call?.takeIf { it.isImplicitThis && !it.isSafe && !it.isSpreadSafe && it.methodAsString != null }
}

internal fun args(call: MethodCallExpression): List<Expression> =
    (call.arguments as? TupleExpression)?.expressions ?: listOf(call.arguments)

internal fun closureOf(arguments: Expression): ClosureExpression? =
    ((arguments as? TupleExpression)?.expressions ?: listOf(arguments)).filterIsInstance<ClosureExpression>()
        .also { if (it.size > 1) fail("more than one closure") }.firstOrNull()

internal fun statementsOf(closure: ClosureExpression): List<Statement> =
    (closure.code as? BlockStatement)?.statements ?: listOf(closure.code)

internal fun stringArg(arguments: List<Expression>): String =
    (arguments.singleOrNull() as? ConstantExpression)?.value as? String ?: fail("expected one string literal")

internal fun literal(expression: Expression): String = when {
    expression is ConstantExpression && expression.value != null -> expression.value.toString()
    expression is UnaryMinusExpression && expression.expression is ConstantExpression &&
        (expression.expression as ConstantExpression).value is Number ->
        "-" + (expression.expression as ConstantExpression).value
    else -> fail("not a literal: ${expression.text}")
}

internal fun xml(text: String): String = buildString {
    text.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(it)
        }
    }
}
