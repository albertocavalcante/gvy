package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.syntax.Types
import org.codenarc.ruleregistry.RuleRegistryHolder

internal val NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

internal fun rule(name: String, properties: Map<String, String>, out: StringBuilder) {
    val ruleClass = requireRule(name)
    out.append("  <rule class=\"").append(xml(ruleClass)).append("\">\n")
    properties.forEach { (key, value) -> property(key, value, "    ", out) }
    out.append("  </rule>\n")
}

/**
 * Rule properties whose value is a regular expression. A regex from an untrusted workspace can be catastrophic
 * (ReDoS) and would run on the analysis thread, so workspace rulesets may not set them.
 */
private fun isRegexProperty(name: String): Boolean =
    name.endsWith("regex", ignoreCase = true) || name.endsWith("FilesMatching") || name.endsWith("Pattern")

internal fun property(key: String, value: String, indent: String, out: StringBuilder) {
    if (isRegexProperty(key)) fail("regular-expression properties are not allowed in workspace rulesets: $key")
    out.append(indent).append("<property name=\"").append(xml(key)).append("\" value=\"")
        .append(xml(value)).append("\"/>\n")
}

internal fun requireRule(name: String): String {
    if (!NAME.matches(name)) fail("bad rule name: $name")
    val ruleClass = runCatching { RuleRegistryHolder.getRuleRegistry().getRuleClass(name) }.getOrNull()
    if (ruleClass == null || !ruleClass.name.startsWith("org.codenarc.rule.")) fail("unknown rule: $name")
    return ruleClass.name
}

/** Literal properties of a rule call: `Rule(prop: 1)` and/or `Rule { prop = 1 }`. */
internal fun properties(call: MethodCallExpression): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (argument in args(call)) {
        when (argument) {
            is NamedArgumentListExpression -> argument.mapEntryExpressions.forEach { entry(it, result) }
            is ClosureExpression -> statementsOf(argument).forEach { assignment(it, result) }
            else -> fail("unsupported argument: ${argument.text}")
        }
    }
    return result
}

internal fun entry(entry: MapEntryExpression, into: MutableMap<String, String>) {
    val key =
        (entry.keyExpression as? ConstantExpression)?.value as? String ?: fail("property names must be literals")
    if (!NAME.matches(key)) fail("bad property name: $key")
    into[key] = literal(entry.valueExpression)
}

internal fun assignment(statement: Statement, into: MutableMap<String, String>) {
    val expression = (statement as? ExpressionStatement)?.expression as? BinaryExpression
        ?: fail("only property assignments are allowed in a rule block")
    val target = expression.leftExpression as? VariableExpression
    if (expression.operation.type != Types.ASSIGN || target == null || !NAME.matches(target.name)) {
        fail("only simple assignments of literals are allowed: ${expression.text}")
    }
    into[target.name] = literal(expression.rightExpression)
}
