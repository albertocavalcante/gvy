package com.github.albertocavalcante.refactor.codenarc.formatting

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to add space around operators.
 *
 * This aligns with CodeNarc rule: SpaceAroundOperator
 *
 * e.g. x=1 -> x = 1
 *
 * @see <a href="https://codenarc.org/codenarc-rules-formatting.html#spacearoundoperator">CodeNarc Rule</a>
 */
class AddSpaceAroundOperator : Recipe() {

    override fun getDisplayName(): String = "Add space around operator"

    override fun getDescription(): String = "Adds a space around operators like +, -, =, etc. if missing."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {

        override fun visitAssignment(assignment: J.Assignment, ctx: ExecutionContext): J.Assignment {
            var a = super.visitAssignment(assignment, ctx)

            // Ensure space before the assignment operator '='
            // The padding 'before' on the assignment property corresponds to the space specifically before the '=' token.
            val rightPadded = a.padding.assignment
            if (rightPadded.before.whitespace.isEmpty()) {
                a = a.padding.withAssignment(
                    rightPadded.withBefore(rightPadded.before.withWhitespace(" ")),
                )
            }

            // Space after '=' (prefix of the right expression)
            if (a.assignment.prefix.whitespace.isEmpty()) {
                a = a.withAssignment(
                    a.assignment.withPrefix(
                        a.assignment.prefix.withWhitespace(" "),
                    ),
                )
            }
            return a
        }

        override fun visitAssignmentOperation(
            assignOp: J.AssignmentOperation,
            ctx: ExecutionContext,
        ): J.AssignmentOperation {
            var a = super.visitAssignmentOperation(assignOp, ctx)

            // Space before operator (+=, -=, etc)
            val opPadding = a.padding.operator
            if (opPadding.before.whitespace.isEmpty()) {
                a = a.padding.withOperator(
                    opPadding.withBefore(opPadding.before.withWhitespace(" ")),
                )
            }

            // Space after operator (prefix of right expression)
            if (a.assignment.prefix.whitespace.isEmpty()) {
                a = a.withAssignment(
                    a.assignment.withPrefix(
                        a.assignment.prefix.withWhitespace(" "),
                    ),
                )
            }
            return a
        }

        override fun visitBinary(binary: J.Binary, ctx: ExecutionContext): J.Binary {
            var b = super.visitBinary(binary, ctx)

            // Space before operator
            val opPadding = b.padding.operator
            if (opPadding.before.whitespace.isEmpty()) {
                b = b.padding.withOperator(
                    opPadding.withBefore(opPadding.before.withWhitespace(" ")),
                )
            }

            // Space after operator (prefix of right expression)
            if (b.right.prefix.whitespace.isEmpty()) {
                b = b.withRight(
                    b.right.withPrefix(
                        b.right.prefix.withWhitespace(" "),
                    ),
                )
            }
            return b
        }

        override fun visitTernary(ternary: J.Ternary, ctx: ExecutionContext): J.Ternary {
            var t = super.visitTernary(ternary, ctx)

            // Space before '?'
            val truePartPadding = t.padding.truePart
            if (truePartPadding.before.whitespace.isEmpty()) {
                t = t.padding.withTruePart(
                    truePartPadding.withBefore(truePartPadding.before.withWhitespace(" ")),
                )
            }

            // Space after '?' (prefix of true part)
            if (t.truePart.prefix.whitespace.isEmpty()) {
                t = t.withTruePart(
                    t.truePart.withPrefix(
                        t.truePart.prefix.withWhitespace(" "),
                    ),
                )
            }

            // Space before ':'
            val falsePartPadding = t.padding.falsePart
            if (falsePartPadding.before.whitespace.isEmpty()) {
                t = t.padding.withFalsePart(
                    falsePartPadding.withBefore(falsePartPadding.before.withWhitespace(" ")),
                )
            }

            // Space after ':' (prefix of false part)
            if (t.falsePart.prefix.whitespace.isEmpty()) {
                t = t.withFalsePart(
                    t.falsePart.withPrefix(
                        t.falsePart.prefix.withWhitespace(" "),
                    ),
                )
            }
            return t
        }

        override fun visitVariableDeclarations(
            multiVariable: J.VariableDeclarations,
            ctx: ExecutionContext,
        ): J.VariableDeclarations {
            var m = super.visitVariableDeclarations(multiVariable, ctx)

            var anyChanged = false
            val newVariables = m.variables.map { nv ->
                var paddedInit = nv.padding.initializer
                if (paddedInit != null) {
                    var changed = false

                    // Space before '='
                    if (paddedInit.before.whitespace.isEmpty()) {
                        paddedInit = paddedInit.withBefore(paddedInit.before.withWhitespace(" "))
                        changed = true
                    }

                    // Space after '=' (prefix of initializer expression)
                    val expression = paddedInit.element
                    if (expression.prefix.whitespace.isEmpty()) {
                        val newExpression = expression.withPrefix<org.openrewrite.java.tree.Expression>(
                            expression.prefix.withWhitespace(" "),
                        )
                        paddedInit = paddedInit.withElement(newExpression)
                        changed = true
                    }

                    if (changed) {
                        anyChanged = true
                        nv.padding.withInitializer(paddedInit)
                    } else {
                        nv
                    }
                } else {
                    nv
                }
            }

            if (anyChanged) {
                m = m.withVariables(newVariables)
            }
            return m
        }
    }
}
