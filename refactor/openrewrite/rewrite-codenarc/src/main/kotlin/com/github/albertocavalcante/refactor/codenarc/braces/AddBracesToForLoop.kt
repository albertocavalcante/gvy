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
 * Recipe to add braces to for loops that don't have them.
 *
 * This aligns with CodeNarc rule: ForStatementBraces
 *
 * @see <a href="https://codenarc.org/codenarc-rules-braces.html#forstatementbraces">CodeNarc Rule</a>
 */
class AddBracesToForLoop : Recipe() {

    override fun getDisplayName(): String = "Add braces to for loops"

    override fun getDescription(): String = "Adds braces to for loops that don't have them."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {

            override fun visitForLoop(forLoop: J.ForLoop, ctx: ExecutionContext): J.ForLoop {
                var f = super.visitForLoop(forLoop, ctx)

                val body = f.body
                if (body !is J.Block) {
                    f = f.withBody(wrapInBlock(body))
                }

                return f
            }

            override fun visitForEachLoop(forLoop: J.ForEachLoop, ctx: ExecutionContext): J.ForEachLoop {
                var f = super.visitForEachLoop(forLoop, ctx)

                val body = f.body
                if (body !is J.Block) {
                    f = f.withBody(wrapInBlock(body))
                }

                return f
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
