// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement

/** A root variable in an unroll pattern; offsets exclude the hash and use an exclusive end. */
data class SpockUnrollPlaceholder(val name: String, val startOffset: Int, val endOffset: Int)

/**
 * Source-level data declarations from a feature method's pre-transform (CONVERSION) AST.
 *
 * This deliberately does not traverse nested closures or infer types from table values. Returned
 * nodes belong to the supplied AST and retain the declaration's source location for navigation.
 */
object SpockDataVariables {
    private const val MAX_DECLARATION_NODES = 4_096
    private val tableOperators = setOf("|", "||")
    private val builtins = setOf("featureName", "iterationIndex", "dataVariables", "dataVariablesWithIndex")
    private val placeholderPattern = Regex("#([a-zA-Z_$][a-zA-Z0-9_$]*)")

    /**
     * Extract pipe-table headers, data-pipe targets and derived data variables in source order.
     * Semicolon-separated tables are not represented by this initial model.
     */
    fun extract(method: MethodNode): Map<String, VariableExpression> {
        val body = method.code as? BlockStatement ?: return emptyMap()
        return DeclarationCollector().apply { body.statements.forEach(::visit) }.declarations
    }

    private class DeclarationCollector {
        val declarations = linkedMapOf<String, VariableExpression>()
        private var inWhere = false
        private var inTable = false

        fun visit(statement: Statement) {
            updateBlock(statement)
            if (!inWhere) return
            val expression = (statement as? ExpressionStatement)?.expression ?: return
            if (expression is BinaryExpression && expression.operation.text in tableOperators) {
                collectTableHeader(expression)
            } else {
                inTable = false
                if (expression is BinaryExpression && expression.operation.text in setOf("<<", "=")) {
                    targets(expression.leftExpression).forEach { addDeclaration(declarations, it) }
                }
            }
        }

        private fun updateBlock(statement: Statement) {
            for (label in statement.statementLabels.orEmpty()) {
                when (label) {
                    "where" -> {
                        inWhere = true
                        inTable = false
                    }
                    "and", "combined" -> inTable = false
                    else -> inWhere = false
                }
            }
        }

        private fun collectTableHeader(expression: Expression) {
            if (!inTable) {
                val columns = tableColumns(expression)
                if (columns != null && columns.all { it is VariableExpression }) {
                    columns.filterIsInstance<VariableExpression>().forEach { addDeclaration(declarations, it) }
                }
            }
            inTable = true
        }
    }

    /** Extract roots only: `#person.name` and `#person.size()` both refer to `person`. */
    fun placeholders(pattern: String): List<SpockUnrollPlaceholder> = placeholderPattern.findAll(pattern).map { match ->
        SpockUnrollPlaceholder(match.groupValues[1], match.range.first + 1, match.range.last + 1)
    }.toList()

    /** An explicit method-level @Unroll pattern replaces the feature-name pattern. */
    fun unrollPatterns(method: MethodNode): List<String> {
        val annotation = method.annotations.firstOrNull {
            it.classNode.name == "spock.lang.Unroll" || it.classNode.name == "Unroll"
        }
        val value = (annotation?.getMember("value") as? ConstantExpression)?.value as? String
        return listOf(value?.takeIf { it.isNotEmpty() } ?: method.name)
    }

    /**
     * Compare roots against the supported declarations and standard built-ins.
     *
     * This is not sufficient to publish diagnostics: callers must account for unsupported table
     * syntax, class-level patterns, @Rollup and workspace unrolling configuration first. Property
     * and method tails are not validated.
     */
    fun unknownPlaceholders(method: MethodNode): List<SpockUnrollPlaceholder> {
        val known = extract(method).keys + builtins
        return unrollPatterns(method).flatMap(::placeholders).filter { it.name !in known }
    }

    private fun tableColumns(expression: Expression): List<Expression>? = flattenBounded(expression) { node ->
        if (node is BinaryExpression && node.operation.text in tableOperators) {
            listOf(node.leftExpression, node.rightExpression)
        } else {
            null
        }
    }

    private fun targets(expression: Expression): List<VariableExpression> = flattenBounded(expression) { node ->
        when (node) {
            is ListExpression -> node.expressions
            is TupleExpression -> node.expressions
            else -> null
        }
    }?.filterIsInstance<VariableExpression>().orEmpty()

    /**
     * Bound work and memory for syntax supplied by a client. Decline the entire declaration when
     * its expansion exceeds the budget so an oversized header cannot introduce partial symbols.
     */
    private fun flattenBounded(
        expression: Expression,
        children: (Expression) -> List<Expression>?,
    ): List<Expression>? {
        val pending = ArrayDeque<Expression>()
        val leaves = mutableListOf<Expression>()
        pending.addLast(expression)
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            visited++
            val expanded = children(node)
            if (expanded == null) {
                leaves.add(node)
            } else {
                if (expanded.size > MAX_DECLARATION_NODES - visited - pending.size) return null
                for (index in expanded.indices.reversed()) pending.addLast(expanded[index])
            }
        }
        return leaves
    }

    private fun addDeclaration(declarations: MutableMap<String, VariableExpression>, variable: VariableExpression) {
        if (variable.name != "_") declarations.putIfAbsent(variable.name, variable)
    }
}
