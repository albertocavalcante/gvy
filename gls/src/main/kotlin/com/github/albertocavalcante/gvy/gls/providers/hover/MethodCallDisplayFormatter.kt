package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import com.github.albertocavalcante.gvy.gls.documentation.SignatureFormatter
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Provides display formatting for method calls and constructor calls.
 */
internal object MethodCallDisplayFormatter {

    fun MarkdownBuilder.renderMethodCallExpression(
        node: MethodCallExpression,
        moduleNode: ModuleNode?,
        methodCallMetadataResolver: MethodCallMetadataResolver?,
    ) {
        val methodName = node.displayMethodName()
        val receiver = node.displayReceiver()
        val arguments = node.displayArguments()
        val callOperator = node.displayCallOperator()

        val signature = buildString {
            if (!node.isImplicitThis) {
                append(receiver)
                append(callOperator)
            }
            append(methodName)
            append("(")
            append(arguments)
            append(")")
        }

        section("Method Call") {
            code("groovy") { signature }
            keyValue(
                "Method" to methodName,
                "Receiver" to receiver,
                "Arguments" to arguments.ifBlank { "none" },
            )

            val methodTarget = node.nodeMetaData["targetMethod"] as? MethodNode
            if (methodTarget != null) {
                markdown("**Resolved Target**")
                code("groovy") { SignatureFormatter.formatMethod(methodTarget) }
                keyValue("Owner" to (methodTarget.declaringClass?.nameWithoutPackage ?: "unknown"))
            } else {
                // Fallback: try classpath/GDK resolution with module context
                methodCallMetadataResolver?.resolveMethodCall(node, moduleNode)?.let { metadata ->
                    markdown("**Resolved Target**")
                    code("groovy") { metadata.signature }
                    keyValue("Owner" to metadata.declaringClass)
                }
            }
        }
    }

    fun MarkdownBuilder.renderConstructorCallExpression(node: ConstructorCallExpression) {
        val className = node.type.nameWithoutPackage
        val arguments = displayConstructorArguments(node)

        val signature = buildString {
            append("new ")
            append(className)
            append("(")
            append(arguments)
            append(")")
        }

        section("Constructor Call") {
            code("groovy") { signature }
            keyValue(
                "Class" to className,
                "Arguments" to arguments.ifBlank { "none" },
            )

            // Show if it's a special call (this() or super())
            if (node.isSuperCall) {
                keyValue("Type" to "super() call")
            } else if (node.isThisCall) {
                keyValue("Type" to "this() call")
            }

            // Show full class name if different from simple name
            val fullClassName = node.type.name
            if (fullClassName != className) {
                keyValue("Full Name" to fullClassName)
            }
        }
    }

    fun displayConstructorArguments(node: ConstructorCallExpression): String {
        val arguments = node.arguments
        return when (arguments) {
            is ArgumentListExpression -> arguments.expressions.map { it.displayArgument() }
            is TupleExpression -> arguments.expressions.map { it.displayArgument() }
            is MapExpression -> arguments.mapEntryExpressions.map { it.displayNamedArgument() }
            else -> listOf(arguments.text.takeIf { it.isNotBlank() } ?: "")
        }.filter { it.isNotBlank() }.joinToString(", ")
    }

    fun MethodCallExpression.displayMethodName(): String =
        methodAsString ?: method.text.takeUnless { it.isNullOrBlank() } ?: "<dynamic>"

    fun MethodCallExpression.displayReceiver(): String = when {
        isImplicitThis -> "this"
        else -> objectExpression?.text?.takeUnless { it.isBlank() } ?: "this"
    }

    fun MethodCallExpression.displayCallOperator(): String = when {
        isSafe -> "?."
        isSpreadSafe -> "*."
        else -> "."
    }

    fun MethodCallExpression.displayArguments(): String {
        val values = when (val expression = arguments) {
            is ArgumentListExpression -> expression.expressions.map { it.displayArgument() }
            is TupleExpression -> expression.expressions.map { it.displayArgument() }
            is MapExpression -> expression.mapEntryExpressions.map { it.displayNamedArgument() }
            else -> listOf(expression.text.takeIf { it.isNotBlank() } ?: "")
        }

        return values.filter { it.isNotBlank() }.joinToString(", ")
    }

    fun Expression.displayArgument(): String = when (this) {
        is ConstantExpression -> text
        is GStringExpression -> text
        is VariableExpression -> name
        is ClosureExpression -> "{ ... }"
        else -> text.takeIf { it.isNotBlank() } ?: toString()
    }

    fun MapEntryExpression.displayNamedArgument(): String {
        val key = keyExpression.displayArgument()
        val value = valueExpression.displayArgument()
        return "$key: $value"
    }
}
