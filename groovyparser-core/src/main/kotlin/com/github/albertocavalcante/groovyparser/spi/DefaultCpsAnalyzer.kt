package com.github.albertocavalcante.groovyparser.spi

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

/**
 * Default implementation of CpsAnalyzer that detects common CPS violations.
 *
 * This analyzer checks for patterns known to cause issues with Jenkins
 * workflow-cps plugin:
 *
 * - Closures passed to standard Groovy collection methods (each, collect, etc.)
 * - Thread operations (Thread.sleep, new Thread, etc.)
 * - Synchronized blocks
 * - Non-whitelisted method calls
 *
 * Note: This is a static analysis tool and may produce false positives.
 * Always test your pipelines in a real Jenkins environment.
 */
class DefaultCpsAnalyzer : CpsAnalyzer {

    /**
     * Methods that commonly cause CPS issues when used with closures.
     */
    private val problematicClosureMethods = setOf(
        "each",
        "eachWithIndex",
        "collect",
        "collectEntries",
        "findAll",
        "find",
        "any",
        "every",
        "grep",
        "groupBy",
        "sort",
        "toSorted",
        "unique",
        "inject",
        "sum",
        "max",
        "min",
        "count",
        "withIndex",
        "indexed",
        "takeWhile",
        "dropWhile",
    )

    /**
     * Methods/classes known to be non-CPS-safe.
     */
    private val nonWhitelistedPatterns = setOf(
        "Thread.sleep",
        "Thread.start",
        "new Thread",
        "synchronized",
        "wait",
        "notify",
        "notifyAll",
        "Runtime.exec",
        "ProcessBuilder",
    )

    override fun isCpsCompatible(node: Node): Boolean = getCpsViolations(node).isEmpty()

    override fun getCpsViolations(node: Node): List<CpsViolation> {
        val violations = mutableListOf<CpsViolation>()
        analyzeNode(node, violations, false)
        return violations
    }

    override fun isNonCps(node: Node): Boolean {
        // In the custom AST, we don't have annotation info directly
        // This would need to be checked against the native AST or via metadata
        // For now, return false - subclasses can override with native AST access
        return false
    }

    private fun analyzeNode(node: Node, violations: MutableList<CpsViolation>, inClosure: Boolean) {
        when (node) {
            is CompilationUnit -> {
                node.types.forEach { analyzeNode(it, violations, inClosure) }
            }
            is ClassDeclaration -> {
                node.methods.forEach { analyzeNode(it, violations, inClosure) }
            }
            is MethodDeclaration -> {
                node.body?.let { analyzeNode(it, violations, inClosure) }
            }
            is BlockStatement -> {
                node.statements.forEach { analyzeNode(it, violations, inClosure) }
            }
            is ExpressionStatement -> {
                analyzeExpression(node.expression, violations, inClosure)
            }
            is IfStatement -> {
                analyzeExpression(node.condition, violations, inClosure)
                analyzeNode(node.thenStatement, violations, inClosure)
                node.elseStatement?.let { analyzeNode(it, violations, inClosure) }
            }
            is ForStatement -> {
                analyzeExpression(node.collectionExpression, violations, inClosure)
                analyzeNode(node.body, violations, inClosure)
            }
            is WhileStatement -> {
                analyzeExpression(node.condition, violations, inClosure)
                analyzeNode(node.body, violations, inClosure)
            }
        }
    }

    private fun analyzeExpression(
        expr: com.github.albertocavalcante.groovyparser.ast.expr.Expression,
        violations: MutableList<CpsViolation>,
        inClosure: Boolean,
    ) {
        when (expr) {
            is MethodCallExpr -> {
                analyzeMethodCall(expr, violations, inClosure)
            }
            is ClosureExpr -> {
                // Closures inside CPS context need special handling
                if (inClosure) {
                    violations.add(
                        CpsViolation(
                            message = "Nested closure detected - may cause CPS serialization issues",
                            position = expr.range?.begin,
                            type = CpsViolationType.NON_SERIALIZABLE_CLOSURE,
                            node = expr,
                        ),
                    )
                }
                // Analyze closure body
                expr.body?.let { analyzeNode(it, violations, true) }
            }
            is com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr -> {
                analyzeExpression(expr.left, violations, inClosure)
                analyzeExpression(expr.right, violations, inClosure)
            }
            is com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr -> {
                analyzeExpression(expr.objectExpression, violations, inClosure)
            }
        }
    }

    private fun analyzeMethodCall(call: MethodCallExpr, violations: MutableList<CpsViolation>, inClosure: Boolean) {
        val methodName = call.methodName

        // Check for problematic closure methods
        if (methodName in problematicClosureMethods) {
            // Check if any argument is a closure
            val hasClosureArg = call.arguments.any { it is ClosureExpr }
            if (hasClosureArg) {
                violations.add(
                    CpsViolation(
                        message = "Method '$methodName' with closure argument may not be CPS-safe. " +
                            "Consider using Jenkins pipeline steps or @NonCPS annotation.",
                        position = call.range?.begin,
                        type = CpsViolationType.NON_SERIALIZABLE_CLOSURE,
                        node = call,
                    ),
                )
            }
        }

        // Check for non-whitelisted patterns
        val fullMethodName = buildMethodSignature(call)
        for (pattern in nonWhitelistedPatterns) {
            if (fullMethodName.contains(pattern, ignoreCase = true) ||
                methodName.equals(pattern, ignoreCase = true)
            ) {
                violations.add(
                    CpsViolation(
                        message = "Method '$fullMethodName' is not CPS-safe. " +
                            "This may cause pipeline serialization failures.",
                        position = call.range?.begin,
                        type = CpsViolationType.NON_WHITELISTED_METHOD,
                        node = call,
                    ),
                )
                break
            }
        }

        // Check for Thread-related calls
        if (methodName == "sleep" || methodName == "start") {
            val objExpr = call.objectExpression
            if (objExpr is com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr &&
                objExpr.name == "Thread"
            ) {
                violations.add(
                    CpsViolation(
                        message = "Thread.$methodName() is not CPS-safe. " +
                            "Use Jenkins 'sleep' step instead.",
                        position = call.range?.begin,
                        type = CpsViolationType.NON_WHITELISTED_METHOD,
                        node = call,
                    ),
                )
            }
        }

        // Recursively analyze object expression and arguments
        call.objectExpression?.let { analyzeExpression(it, violations, inClosure) }
        call.arguments.forEach { analyzeExpression(it, violations, inClosure) }
    }

    private fun buildMethodSignature(call: MethodCallExpr): String {
        val objExpr = call.objectExpression
        return if (objExpr != null) {
            when (objExpr) {
                is com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr ->
                    "${objExpr.name}.${call.methodName}"
                is com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr ->
                    "${objExpr.propertyName}.${call.methodName}"
                else -> call.methodName
            }
        } else {
            call.methodName
        }
    }
}
