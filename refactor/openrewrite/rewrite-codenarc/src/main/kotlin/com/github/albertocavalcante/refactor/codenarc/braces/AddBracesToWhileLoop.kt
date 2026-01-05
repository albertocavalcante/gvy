package com.github.albertocavalcante.refactor.codenarc.braces

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Space
import org.openrewrite.java.tree.Statement
import org.openrewrite.marker.Markers
import java.util.UUID

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
                    w = w.withBody(wrapInBlock(body))
                }

                return w
            }

            private fun wrapInBlock(statement: Statement): J.Block {
                val indentedStatement: Statement = statement.withPrefix<Statement>(
                    Space.format("\n    "),
                )

                val paddedStatement: JRightPadded<Statement> = JRightPadded(
                    indentedStatement,
                    Space.EMPTY,
                    Markers.EMPTY,
                )

                return J.Block(
                    UUID.randomUUID(),
                    Space.SINGLE_SPACE,
                    Markers.EMPTY,
                    JRightPadded(false, Space.EMPTY, Markers.EMPTY),
                    listOf(paddedStatement),
                    Space.format("\n"),
                )
            }
        }
    }
}
