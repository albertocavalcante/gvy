package com.github.albertocavalcante.refactor.codenarc.braces

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to add braces to while loops that don't have them.
 *
 * This aligns with CodeNarc rule: WhileStatementBraces
 *
 * @see <a href="https://codenarc.org/codenarc-rules-braces.html#whilestatementbraces">CodeNarc Rule</a>
 */
class AddBracesToWhileLoop : Recipe() {

    override fun getDisplayName(): String = "Add braces to while loops"

    override fun getDescription(): String = "Adds braces to while loops that don't have them."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {

            override fun visitWhileLoop(whileLoop: J.WhileLoop, ctx: ExecutionContext): J.WhileLoop {
                var w = super.visitWhileLoop(whileLoop, ctx)

                val body = w.body
                if (body !is J.Block) {
                    w = w.withBody(BraceUtils.wrapInBlock(body))
                }

                return w
            }
        }
    }
}
