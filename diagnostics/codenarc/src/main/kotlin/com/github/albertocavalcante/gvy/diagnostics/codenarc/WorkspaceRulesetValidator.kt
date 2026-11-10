package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types

/**
 * Decides whether a Groovy-DSL CodeNarc ruleset that came from the workspace is safe to hand to CodeNarc.
 *
 * CodeNarc evaluates such a file as a Groovy script, so a ruleset from an untrusted workspace could run
 * arbitrary code. Only the declarative subset is accepted: a single top-level `ruleset { ... }` whose body is
 * made of rule names, calls with literal arguments (`ruleset('rulesets/basic.xml') { exclude 'X' }`,
 * `description '...'`), nested configuration closures and assignments of literals (`priority = 2`).
 * Constructors, qualified or static calls, property access, expression GStrings, imports, extra classes or
 * methods and any other top-level statement are rejected. The file is parsed without running any compiler
 * phase operation, so no transform can execute while checking it.
 */
internal object WorkspaceRulesetValidator {
    /** The reason the ruleset is not acceptable, or null when it is. */
    fun rejection(text: String): String? {
        val module = parse(text) ?: return "not parseable as Groovy"
        val hasExtraDeclarations = module.imports.isNotEmpty() || module.starImports.isNotEmpty() ||
            module.staticImports.isNotEmpty() || module.staticStarImports.isNotEmpty() ||
            module.packageName != null || module.methods.isNotEmpty() || module.classes.size > 1
        val top = (module.statementBlock as BlockStatement).statements.singleOrNull()
        return when {
            hasExtraDeclarations -> "imports, package, classes or methods are not allowed"
            top == null -> "expected a single top-level ruleset { ... }"
            else -> topLevelRejection(top)
        }
    }

    /** Parses and converts to an AST without running any phase operation (so no transform can execute). */
    private fun parse(text: String): ModuleNode? = try {
        val config = CompilerConfiguration()
        SourceUnit("workspace-ruleset", text, config, null, ErrorCollector(config)).run {
            parse()
            completePhase()
            nextPhase()
            convert()
            ast
        }
    } catch (_: CompilationFailedException) {
        null
    }

    private fun topLevelRejection(statement: Statement): String? {
        val call = (statement as? ExpressionStatement)?.expression as? MethodCallExpression
        return if (call == null || call.methodAsString != "ruleset" || !call.isImplicitThis) {
            "the top-level statement must be ruleset { ... }"
        } else {
            callRejection(call)
        }
    }

    private fun callRejection(call: MethodCallExpression): String? {
        val name = call.methodAsString
        return when {
            !call.isImplicitThis || name == null || call.isSpreadSafe || call.isSafe -> "qualified call: ${call.text}"
            else -> argumentsRejection(call.arguments)
        }
    }

    private fun argumentsRejection(arguments: Expression): String? {
        val items = if (arguments is TupleExpression) arguments.expressions else listOf(arguments)
        return items.firstNotNullOfOrNull { valueRejection(it) }
    }

    private fun valueRejection(expression: Expression): String? = when (expression) {
        is ConstantExpression -> null
        is GStringExpression -> expression.takeIf {
            it.values.isNotEmpty()
        }?.let { "GString with expressions: ${it.text}" }
        is ListExpression -> expression.expressions.firstNotNullOfOrNull { valueRejection(it) }
        is NamedArgumentListExpression, is MapExpression -> (expression as MapExpression).mapEntryExpressions
            .firstNotNullOfOrNull { valueRejection(it) }
        is MapEntryExpression -> valueRejection(expression.keyExpression) ?: valueRejection(expression.valueExpression)
        is UnaryMinusExpression -> valueRejection(expression.expression)
        is ClosureExpression -> bodyRejection(expression.code)
        else -> "not a literal: ${expression.text}"
    }

    private fun bodyRejection(code: Statement): String? {
        val statements = (code as? BlockStatement)?.statements ?: listOf(code)
        return statements.firstNotNullOfOrNull { statementRejection(it) }
    }

    private fun statementRejection(statement: Statement): String? {
        val expression = (statement as? ExpressionStatement)?.expression
            ?: return "unsupported statement: ${statement.text}"
        return when (expression) {
            // A bare rule name such as `TrailingWhitespace`
            is VariableExpression -> "this/super".takeIf { expression.isThisExpression || expression.isSuperExpression }
            is MethodCallExpression -> callRejection(expression)
            is BinaryExpression -> assignmentRejection(expression)
            else -> "unsupported expression: ${expression.text}"
        }
    }

    private fun assignmentRejection(expression: BinaryExpression): String? {
        val isAssignment = expression.operation.type == Types.ASSIGN
        val target = expression.leftExpression as? VariableExpression
        return if (!isAssignment || target == null) {
            "only simple assignments of literals are allowed: ${expression.text}"
        } else {
            valueRejection(expression.rightExpression)
        }
    }
}
