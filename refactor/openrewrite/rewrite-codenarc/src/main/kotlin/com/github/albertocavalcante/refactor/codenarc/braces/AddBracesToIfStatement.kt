package com.github.albertocavalcante.refactor.codenarc.braces

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to add braces to if statements that don't have them.
 *
 * This aligns with CodeNarc rule: IfStatementBraces
 *
 * Single-line if statements without braces are converted to properly
 * braced blocks for better readability and maintainability.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-braces.html#ifstatementbraces">CodeNarc Rule</a>
 */
class AddBracesToIfStatement : Recipe() {

    override fun getDisplayName(): String = "Add braces to if statements"

    override fun getDescription(): String = "Adds braces to if statements that don't have them. " +
        "Single-line if statements are converted to braced blocks."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {

            override fun visitIf(iff: J.If, ctx: ExecutionContext): J.If {
                var i = super.visitIf(iff, ctx)

                // Check if the then part needs braces
                val thenPart = i.thenPart
                if (thenPart !is J.Block) {
                    i = i.withThenPart(BraceUtils.wrapInBlock(thenPart))
                }

                // Check if the else part needs braces
                val elsePart = i.elsePart
                if (elsePart != null) {
                    val elseBody = elsePart.body
                    // Don't wrap if it's already a block or another if (else-if chain)
                    if (elseBody !is J.Block && elseBody !is J.If) {
                        i = i.withElsePart(elsePart.withBody(BraceUtils.wrapInBlock(elseBody)))
                    }
                }

                return i
            }
        }
    }
}
