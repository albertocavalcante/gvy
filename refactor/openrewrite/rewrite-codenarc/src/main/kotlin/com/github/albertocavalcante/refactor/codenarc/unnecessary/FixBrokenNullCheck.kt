package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to fix broken null checks where || is used instead of &&.
 *
 * This aligns with CodeNarc rule: BrokenNullCheck
 *
 * Scans for comparisons where a variable is checked for non-nullity using `!= null`
 * and then immediately dereferenced, but the two checks are connected by `||`
 * instead of `&&`. This causes a crash if the variable is null.
 *
 * e.g. `x != null || x.prop` → `x != null && x.prop`
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#brokennullcheck">CodeNarc Rule</a>
 */
class FixBrokenNullCheck : Recipe() {

    override fun getDisplayName(): String = "Fix broken null check"

    override fun getDescription(): String =
        "Fixes a common bug where `!= null` check is combined with dereference using `||` instead of `&&`."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {

        override fun visitBinary(binary: J.Binary, ctx: ExecutionContext): J {
            var b = super.visitBinary(binary, ctx) as J.Binary

            // We are looking for OR operators: ||
            if (b.operator != J.Binary.Type.Or) {
                return b
            }

            val left = b.left
            val right = b.right

            // Check pattern: check != null || dereference
            // We need to identify if 'left' is a null check for a variable,
            // and 'right' is a dereference of THAT SAME variable.

            val nullCheckVar = getNotNullCheckVariable(left)

            if (nullCheckVar != null) {
                if (isDereferenceOf(right, nullCheckVar)) {
                    // Fix: Change operator to AND
                    // We need to preserve formatting, so we recreate the binary expression
                    return b.withOperator(J.Binary.Type.And)
                }
            }

            // Match direct dereferences (field access, method call, instanceof) of the variable.
            // This implementation handles the common `!= null` pattern;
            // it does not cover all variants checked by CodeNarc.
            return b
        }

        private fun getNotNullCheckVariable(expr: J): String? {
            if (expr !is J.Binary) return null
            if (expr.operator != J.Binary.Type.NotEqual) return null

            val left = expr.left
            val right = expr.right

            return when {
                left is J.Identifier && right is J.Literal && right.value == null -> left.simpleName
                right is J.Identifier && left is J.Literal && left.value == null -> right.simpleName
                else -> null
            }
        }

        private fun isDereferenceOf(expr: J, varName: String): Boolean = when (expr) {
            is J.FieldAccess -> {
                // x.prop
                val target = expr.target
                target is J.Identifier && target.simpleName == varName
            }

            is J.MethodInvocation -> {
                // x.method()
                val select = expr.select
                select is J.Identifier && select.simpleName == varName
            }

            is J.InstanceOf -> {
                // x instanceof Type
                val expression = expr.expression
                expression is J.Identifier && expression.simpleName == varName
            }

            else -> false
        }
    }
}
