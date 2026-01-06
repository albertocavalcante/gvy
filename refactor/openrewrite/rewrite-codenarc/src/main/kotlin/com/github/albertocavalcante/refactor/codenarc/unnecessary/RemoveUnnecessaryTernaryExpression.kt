package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JLeftPadded
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Space
import org.openrewrite.marker.Markers

/**
 * Recipe to remove unnecessary ternary expressions.
 *
 * This aligns with CodeNarc rule: UnnecessaryTernaryExpression
 *
 * e.g. x ? true : false -> x
 *      x ? false : true -> !x
 *      x ? y : y -> y
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessaryternaryexpression">CodeNarc Rule</a>
 */
class RemoveUnnecessaryTernaryExpression : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary ternary expression"

    override fun getDescription(): String =
        "Simplifies ternary expressions that are redundant or return simple booleans."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {

        override fun visitTernary(ternary: J.Ternary, ctx: ExecutionContext): J {
            val t = super.visitTernary(ternary, ctx) as J.Ternary
            val (truePart, falsePart) = t.truePart to t.falsePart

            return when {
                // Case 1: Both branches are the same -> use either branch
                areEquivalent(truePart, falsePart) -> truePart.withPrefix(t.prefix)

                // Case 2: cond ? true : false -> cond
                truePart.isTrue() && falsePart.isFalse() -> t.condition.withPrefix(t.prefix)

                // Case 3: cond ? false : true -> !cond
                truePart.isFalse() && falsePart.isTrue() -> createNegation(t)

                else -> t
            }
        }

        private fun Expression.isTrue(): Boolean = when {
            this is J.Literal && value == true -> true
            this is J.FieldAccess && name.simpleName == "TRUE" &&
                (target as? J.Identifier)?.simpleName == "Boolean" -> true

            else -> false
        }

        private fun Expression.isFalse(): Boolean = when {
            this is J.Literal && value == false -> true
            this is J.FieldAccess && name.simpleName == "FALSE" &&
                (target as? J.Identifier)?.simpleName == "Boolean" -> true

            else -> false
        }

        private fun areEquivalent(e1: Expression, e2: Expression): Boolean = when {
            e1 is J.Literal && e2 is J.Literal -> e1.value == e2.value
            e1 is J.Identifier && e2 is J.Identifier -> e1.simpleName == e2.simpleName
            else -> false
        }

        private fun createNegation(t: J.Ternary): J.Unary {
            // Wrap in parens if needed for operator precedence
            val condition = when (t.condition) {
                is J.Binary, is J.Ternary -> J.Parentheses(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    JRightPadded.build(t.condition),
                )

                else -> t.condition
            }

            return J.Unary(
                Tree.randomId(),
                t.prefix,
                t.markers,
                JLeftPadded.build(J.Unary.Type.Not),
                condition,
                t.type,
            )
        }
    }
}
