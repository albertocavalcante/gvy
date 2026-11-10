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

/**
 * A property value from a workspace ruleset: only int, long, decimal and boolean literals. String values are not
 * accepted: several rule properties (`applyToClassNames`, `ignoreMethodNames`, ...) are compiled by CodeNarc into
 * regular expressions with wildcards expanded to `.*`, which can be made catastrophic (ReDoS) on the analysis
 * thread. Vetted string properties can be added to an allow-list later (tracked in #1324).
 */
internal fun literal(expression: Expression): String {
    val constant = when {
        expression is ConstantExpression -> expression.value
        expression is UnaryMinusExpression && (expression.expression as? ConstantExpression)?.value is Number ->
            return "-" + (expression.expression as ConstantExpression).value
        else -> null
    }
    return when (constant) {
        is Boolean, is Int, is Long, is java.math.BigDecimal -> constant.toString()
        else -> fail("property values must be int, long, decimal or boolean literals: ${expression.text}")
    }
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
