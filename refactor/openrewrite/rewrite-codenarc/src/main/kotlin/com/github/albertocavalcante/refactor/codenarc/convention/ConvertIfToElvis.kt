package com.github.albertocavalcante.refactor.codenarc.convention

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.JavaTemplate
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Space
import org.openrewrite.java.tree.Statement

/**
 * Recipe to convert standard if-null/false assignment to Elvis operator.
 *
 * This aligns with CodeNarc rule: CouldBeElvis
 *
 * Converts:
 * - `if (!x) x = y` → `x = x ?: y`
 * - `if (x == null) x = y` → `x = x ?: y`
 *
 * Checks that the if body contains ONLY the assignment to the same variable being checked.
 *
 * Note: Known issue - output has missing space after ?: operator (produces "x = x ?:y" instead of "x = x ?: y")
 *       The transformation logic is correct, but GroovyPrinter spacing needs investigation.
 *       Upstream issue: https://github.com/openrewrite/rewrite/issues/6482
 *
 * @see <a href="https://codenarc.org/codenarc-rules-convention.html#couldbeelvis">CodeNarc Rule</a>
 */
class ConvertIfToElvis : Recipe() {

    override fun getDisplayName(): String = "Convert if to Elvis operator"

    override fun getDescription(): String =
        "Converts if statements that check for null/false and assign a default value to use the Elvis operator."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {

        override fun visitIf(iff: J.If, ctx: ExecutionContext): J {
            // Check patterns: if (!x) or if (x == null)
            val conditionVar = getConditionVariable(iff.ifCondition.tree) ?: return super.visitIf(iff, ctx)

            // Check body: Must be single statement assignment: x = value
            val (assignmentTargetName, assignmentValue) = getAssignment(iff.thenPart) ?: return super.visitIf(iff, ctx)

            // The assignment target must match the condition variable
            if (assignmentTargetName != conditionVar) {
                return super.visitIf(iff, ctx)
            }

            // Ensure no else part
            if (iff.elsePart != null) {
                return super.visitIf(iff, ctx)
            }

            // Transform to Elvis: x = x ?: y
            val template = JavaTemplate.builder("#{any()} = #{any()} ?: #{any()}")
                .contextSensitive()
                .build()

            // We need a J.Identifier for the variable to pass to the template.
            val targetIdentifier = getAssignmentIdentifier(iff.thenPart) ?: return super.visitIf(iff, ctx)

            // Explicitly force a single space on the assignment value to ensure " ?: value" spacing
            val singleSpace = Space.build(" ", emptyList())

            return template.apply(
                cursor,
                iff.coordinates.replace(),
                targetIdentifier.withPrefix(Space.EMPTY),
                targetIdentifier.withPrefix(Space.EMPTY),
                assignmentValue.withPrefix(singleSpace),
            )
        }

        @Suppress("ReturnCount") // Guard clauses for early exit validation
        private fun getConditionVariable(expr: J): String? {
            // Pattern 1: !x
            if (expr is J.Unary && expr.operator == J.Unary.Type.Not) {
                val operand = expr.expression
                if (operand is J.Identifier) return operand.simpleName
            }

            // Pattern 2: x == null
            if (expr is J.Binary && expr.operator == J.Binary.Type.Equal) {
                val left = expr.left
                val right = expr.right
                if (left is J.Identifier && right is J.Literal && right.value == null) return left.simpleName
                if (right is J.Identifier && left is J.Literal && left.value == null) return right.simpleName
            }

            return null
        }

        private fun getAssignmentIdentifier(stmt: Statement): J.Identifier? {
            val bodyStmt = if (stmt is J.Block) {
                if (stmt.statements.size != 1) return null
                stmt.statements[0]
            } else {
                stmt
            }

            if (bodyStmt is J.Assignment) {
                val variable = bodyStmt.variable
                if (variable is J.Identifier) {
                    return variable
                }
            }
            return null
        }

        private fun getAssignment(stmt: Statement): Pair<String, J>? {
            val identifier = getAssignmentIdentifier(stmt) ?: return null
            val bodyStmt = if (stmt is J.Block) stmt.statements[0] as J.Assignment else stmt as J.Assignment
            return identifier.simpleName to bodyStmt.assignment
        }
    }
}
