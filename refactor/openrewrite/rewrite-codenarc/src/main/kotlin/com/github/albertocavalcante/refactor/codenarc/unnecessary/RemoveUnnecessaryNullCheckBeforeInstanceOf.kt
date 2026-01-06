package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to remove unnecessary null checks before instanceof.
 *
 * This aligns with CodeNarc rule: UnnecessaryNullCheckBeforeInstanceOf
 *
 * The instanceof operator in Groovy/Java returns false when given a null argument,
 * making a null check before instanceof redundant.
 *
 * e.g. `x != null && x instanceof String` → `x instanceof String`
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarynullcheckbeforeinstanceof">CodeNarc Rule</a>
 */
class RemoveUnnecessaryNullCheckBeforeInstanceOf : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary null check before instanceof"

    override fun getDescription(): String =
        "Removes redundant null checks before instanceof, since instanceof returns false for null values."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {

        override fun visitBinary(binary: J.Binary, ctx: ExecutionContext): J {
            val b = super.visitBinary(binary, ctx) as J.Binary

            // Only handle && operator
            if (b.operator != J.Binary.Type.And) {
                return b
            }

            // Check for pattern: nullCheck && instanceof OR instanceof && nullCheck
            val left = b.left
            val right = b.right

            // Pattern 1: x != null && x instanceof Type
            if (isNotNullCheck(left) && isInstanceOf(right)) {
                val nullCheckVar = getNullCheckVariable(left)
                val instanceOfVar = getInstanceOfVariable(right)
                if (nullCheckVar != null && nullCheckVar == instanceOfVar) {
                    return right.withPrefix(b.prefix)
                }
            }

            // Pattern 2: x instanceof Type && x != null
            if (isInstanceOf(left) && isNotNullCheck(right)) {
                val instanceOfVar = getInstanceOfVariable(left)
                val nullCheckVar = getNullCheckVariable(right)
                if (nullCheckVar != null && nullCheckVar == instanceOfVar) {
                    return left.withPrefix(b.prefix)
                }
            }

            return b
        }

        private fun isNotNullCheck(expr: J): Boolean {
            if (expr !is J.Binary) return false
            if (expr.operator != J.Binary.Type.NotEqual) return false

            val left = expr.left
            val right = expr.right

            return (left is J.Identifier && right is J.Literal && right.value == null) ||
                (right is J.Identifier && left is J.Literal && left.value == null)
        }

        private fun isInstanceOf(expr: J): Boolean = expr is J.InstanceOf

        private fun getNullCheckVariable(expr: J): String? {
            if (expr !is J.Binary) return null
            val left = expr.left
            val right = expr.right

            return when {
                left is J.Identifier && right is J.Literal && right.value == null -> left.simpleName
                right is J.Identifier && left is J.Literal && left.value == null -> right.simpleName
                else -> null
            }
        }

        private fun getInstanceOfVariable(expr: J): String? {
            if (expr !is J.InstanceOf) return null
            val expression = expr.expression
            return if (expression is J.Identifier) expression.simpleName else null
        }
    }
}
