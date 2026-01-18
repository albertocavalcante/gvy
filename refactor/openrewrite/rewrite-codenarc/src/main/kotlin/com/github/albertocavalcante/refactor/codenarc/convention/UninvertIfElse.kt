package com.github.albertocavalcante.refactor.codenarc.convention

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to uninvert if-else statements when the condition is negated.
 *
 * This aligns with CodeNarc rule: UninvertIfElse
 *
 * e.g. if (!x) { a } else { b } -> if (x) { b } else { a }
 *
 * @see <a href="https://codenarc.org/codenarc-rules-convention.html#uninvertifelse">CodeNarc Rule</a>
 */
class UninvertIfElse : Recipe() {
    override fun getDisplayName() = "Uninvert if-else"
    override fun getDescription() = "Inverts if control flow when the condition is negated."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {
        @Suppress("ReturnCount") // Guard clauses for early exit validation
        override fun visitIf(iff: J.If, ctx: ExecutionContext): J {
            val i = super.visitIf(iff, ctx) as J.If
            if (i.elsePart == null) return i

            val condition = i.ifCondition.tree

            // Handle !x
            if (condition is J.Unary && condition.operator == J.Unary.Type.Not) {
                return invert(i, condition.expression)
            }

            // Handle x != y
            if (condition is J.Binary && condition.operator == J.Binary.Type.NotEqual) {
                val newCondition = condition.withOperator(J.Binary.Type.Equal)
                return invert(i, newCondition)
            }

            return i
        }

        private fun invert(iff: J.If, newCondition: org.openrewrite.java.tree.Expression): J.If {
            val thenPart = iff.thenPart
            val elsePart = iff.elsePart!!.body

            // Swap bodies, preserving prefix of thenPart?
            // iff.thenPart prefix usually contains newline+indent.
            // iff.elsePart.body prefix usually contains newline+indent.

            // We need to be careful with formatting.
            // The logic:
            // if (newCondition) { elseBody } else { thenBody }

            return iff.withIfCondition(iff.ifCondition.withTree(newCondition))
                .withThenPart(elsePart.withPrefix(thenPart.prefix))
                .withElsePart(iff.elsePart!!.withBody(thenPart.withPrefix(elsePart.prefix)))
        }
    }
}
