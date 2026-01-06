package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J

/**
 * Recipe to simplify ternary expressions to Elvis operator.
 *
 * This aligns with CodeNarc rule: SimplifyTernaryToElvis
 *
 * e.g. x ? x : y -> x ?: y
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
                }
            }
            return t
        }

        /**
         * Checks if two expressions are structurally equivalent.
         * This is heuristic and conservative; we only simplify if we are sure they are the same.
         */
        private fun areEquivalent(e1: Expression, e2: Expression): Boolean {
            if (e1 === e2) return true

            // Unwrap parentheses to compare the core expressions
            val u1 = unwrap(e1)
            val u2 = unwrap(e2)

            if (u1.javaClass != u2.javaClass) return false

            return when (u1) {
                // J.Empty often represents an implicit receiver in method invocations (e.g. `foo()` is `J.Empty.foo()`)
                // Two empty expressions are effectively equivalent here.
                is J.Empty -> true

                is J.Identifier -> {
                    u2 as J.Identifier
                    u1.simpleName == u2.simpleName
                }

                is J.Literal -> {
                    u2 as J.Literal
                    // Compare valueSource to ensure exact same source representation (e.g. hex vs decimal, string quotes)
                    u1.valueSource == u2.valueSource
                }

                is J.MethodInvocation -> {
                    u2 as J.MethodInvocation
                    if (u1.simpleName != u2.simpleName) return false

                    // Check select (receiver), which might be null or J.Empty
                    if (!areEquivalentOrNull(u1.select, u2.select)) return false

                    // Check arguments recursively
                    if (u1.arguments.size != u2.arguments.size) return false
                    u1.arguments.zip(u2.arguments).all { (a1, a2) -> areEquivalent(a1, a2) }
                }

                // Conservative default: if we don't know the type, assume they are different.
                // We intentionally avoid side-effecting expressions like assignment or increment/decrement
                // unless we could guarantee they are pure (which we can't easily here).
                else -> false
            }
        }

        private fun areEquivalentOrNull(e1: Expression?, e2: Expression?): Boolean {
            if (e1 == null && e2 == null) return true
            if (e1 == null || e2 == null) return false
            return areEquivalent(e1, e2)
        }

        /**
         * Unwraps expressions from containers that don't affect semantic value for this check,
         * such as parenthesized groups.
         */
        private fun unwrap(e: Expression): Expression {
            var current = e
            while (current is J.Parentheses<*>) {
                current = (current as J.Parentheses<*>).tree as Expression
            }
            return current
        }
    }
}
