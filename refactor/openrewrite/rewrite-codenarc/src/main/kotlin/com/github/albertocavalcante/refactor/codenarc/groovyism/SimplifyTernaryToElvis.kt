package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Space

/**
 * Recipe to simplify ternary expressions to Elvis operator.
 *
 * This aligns with CodeNarc rule: SimplifyTernaryToElvis
 *
 * e.g. x ? x : y -> x ?: y
 *
 * @see <a href="https://codenarc.github.io/CodeNarc/codenarc-rules-groovyism.html#simplifyternarytoelvis">
 *     CodeNarc Rule</a>
 */
class SimplifyTernaryToElvis : Recipe() {

    override fun getDisplayName(): String = "Simplify ternary to elvis"

    override fun getDescription(): String = "Replaces `x ? x : y` with `x ?: y`."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {
        override fun visitTernary(ternary: J.Ternary, ctx: ExecutionContext): J.Ternary {
            var t = super.visitTernary(ternary, ctx)

            if (areEquivalent(t.condition, t.truePart)) {
                // The Elvis operator (x ?: y) is semantically equivalent to (x ? x : y).
                // In OpenRewrite's Groovy AST, an Elvis operator is represented as a J.Ternary
                // with a specific 'Elvis' marker attached.
                //
                // When this marker is present, the GroovyPrinter will output '?:' and skip printing
                // the true part of the ternary (which is implicitly the condition).
                //
                // We do NOT technically remove the truePart from the J.Ternary node structure itself;
                // it remains a valid J.Ternary, but the marker dictates the printed representation.
                if (!t.markers.findFirst(org.openrewrite.groovy.marker.Elvis::class.java).isPresent) {
                    t =
                        t.withMarkers(
                            t.markers.addIfAbsent(org.openrewrite.groovy.marker.Elvis(org.openrewrite.Tree.randomId())),
                        )
                    // Groovy's Elvis printer retains both the old colon padding and the fallback's
                    // prefix. Drop redundant inline separator whitespace while keeping comments.
                    val fallback = t.padding.falsePart
                    val before = fallback.before
                    if (before.comments.isEmpty() && '\n' !in before.whitespace && '\r' !in before.whitespace) {
                        t = t.padding.withFalsePart(fallback.withBefore(Space.EMPTY))
                    }
                }
            }
            return t
        }

        /**
         * Checks if two expressions are structurally equivalent.
         * Only literals qualify. Identifier reads can invoke getters, and user-defined Groovy
         * truthiness can change even a local binding before the repeated read.
         * TODO(#1651): prove binding and truthiness safety before enabling identifier simplification.
         *   See: https://github.com/albertocavalcante/gvy/issues/1651
         */
        private fun areEquivalent(e1: Expression, e2: Expression): Boolean {
            // The condition remains in the output, so its parentheses and comments are preserved.
            // The repeated branch is removed; accept only a literal with no prefix comment.
            val u1 = unwrap(e1)
            val u2 = e2
            if (u2.prefix.comments.isNotEmpty()) return false

            return when (u1) {
                // Preserve the exact literal representation, including quoting and number format.
                is J.Literal -> u2 is J.Literal && u1.valueSource == u2.valueSource

                // Conservative default: if we don't know the type, assume they are different.
                // We intentionally avoid side-effecting expressions like assignment or increment/decrement
                // unless we could guarantee they are pure (which we can't easily here).
                else -> false
            }
        }

        /**
         * Unwraps expressions from containers that don't affect semantic value for this check,
         * such as parenthesized groups.
         */
        private fun unwrap(e: Expression): Expression {
            var current = e
            while (current is J.Parentheses<*>) {
                current = current.tree as Expression
            }
            return current
        }
    }
}
