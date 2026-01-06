package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J

/**
 * Recipe to remove unnecessary toString() calls.
 *
 * This aligns with CodeNarc rule: UnnecessaryToString
 *
 * Removes redundant toString() calls in contexts where the conversion is implicit:
 * - On String-typed expressions (e.g., "foo".toString())
 * - Inside GString interpolation (e.g., "${x.toString()}" → "${x}")
 * - When concatenating with a String (e.g., "a" + x.toString())
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarytostring">CodeNarc Rule</a>
 */
class RemoveUnnecessaryToString : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary toString() calls"

    override fun getDescription(): String =
        "Removes redundant toString() calls on String expressions, in GString interpolation, " +
            "or when concatenating with Strings."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {

        override fun visitMethodInvocation(method: J.MethodInvocation, ctx: ExecutionContext): J {
            val m = super.visitMethodInvocation(method, ctx) as J.MethodInvocation

            // Only target toString() with no arguments
            if (!isNoArgToString(m)) {
                return m
            }

            val receiver = m.select ?: return m

            // Case 1: toString() on a String literal
            if (receiver is J.Literal && receiver.value is String) {
                return receiver.withPrefix(m.prefix)
            }

            // Case 2: toString() on a GString (already a String)
            if (receiver is G.GString) {
                return receiver.withPrefix(m.prefix)
            }

            return m
        }

        override fun visitGString(gString: G.GString, ctx: ExecutionContext): J {
            val g = super.visitGString(gString, ctx) as G.GString

            // Check GString values for unnecessary toString() calls
            var changed = false
            val newStrings = g.strings.map { part ->
                if (part is J.MethodInvocation && isNoArgToString(part)) {
                    changed = true
                    part.select?.withPrefix(part.prefix) ?: part
                } else {
                    part
                }
            }

            return if (changed) g.withStrings(newStrings) else g
        }

        override fun visitBinary(binary: J.Binary, ctx: ExecutionContext): J {
            val b = super.visitBinary(binary, ctx) as J.Binary

            // Check for String + x.toString() pattern
            if (b.operator == J.Binary.Type.Addition) {
                val leftIsString = isStringExpression(b.left)
                val rightIsToString = b.right is J.MethodInvocation && isNoArgToString(b.right as J.MethodInvocation)

                if (leftIsString && rightIsToString) {
                    val toStringCall = b.right as J.MethodInvocation
                    val receiver = toStringCall.select
                    if (receiver != null) {
                        return b.withRight(receiver.withPrefix(toStringCall.prefix))
                    }
                }
            }

            return b
        }

        private fun isNoArgToString(method: J.MethodInvocation): Boolean {
            if (method.simpleName != "toString") return false
            val args = method.arguments
            return args.isEmpty() || (args.size == 1 && args[0] is J.Empty)
        }

        private fun isStringExpression(expr: Expression): Boolean = when (expr) {
            is J.Literal -> expr.value is String
            is G.GString -> true
            else -> false
        }
    }
}
