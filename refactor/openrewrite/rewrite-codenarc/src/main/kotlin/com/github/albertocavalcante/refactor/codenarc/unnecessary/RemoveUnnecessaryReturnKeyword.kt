package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.groovy.marker.ImplicitReturn
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Space

/**
 * Recipe to remove unnecessary return keyword from the last statement of a method or closure.
 *
 * This aligns with CodeNarc rule: UnnecessaryReturnKeyword
 *
 * e.g. return "foo" -> "foo"
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessaryreturnkeyword">CodeNarc Rule</a>
 */
class RemoveUnnecessaryReturnKeyword : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary return keyword"

    override fun getDescription(): String =
        "Removes the `return` keyword from the last statement in a method or closure when it is implicit."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {

        override fun visitMethodDeclaration(method: J.MethodDeclaration, ctx: ExecutionContext): J.MethodDeclaration {
            val m = super.visitMethodDeclaration(method, ctx)
            val body = m.body ?: return m
            val newBody = processBlock(body)
            if (newBody !== body) {
                return m.withBody(newBody)
            }
            return m
        }

        override fun visitLambda(lambda: J.Lambda, ctx: ExecutionContext): J.Lambda {
            val l = super.visitLambda(lambda, ctx)
            if (l.body is J.Block) {
                val body = l.body as J.Block
                val newBody = processBlock(body)
                if (newBody !== body) {
                    return l.withBody(newBody)
                }
            }
            return l
        }

        private fun processBlock(block: J.Block): J.Block {
            val statements = block.statements
            if (statements.isEmpty()) {
                return block
            }

            val lastIdx = statements.size - 1
            val lastStat = statements[lastIdx]

            // We only care if the last statement is an explicit 'return' with an expression
            // e.g. 'return 1' vs 'return' (void) or just '1'
            if (lastStat is J.Return && lastStat.expression != null) {
                // Check if already marked as implicit (shouldn't happen for J.Return usually, but good safety)
                if (lastStat.markers.findFirst(ImplicitReturn::class.java).isPresent) {
                    return block
                }

                // In Groovy, the last expression is implicitly returned.
                // To remove 'return', we simply convert the J.Return statement into its expression
                // BUT we must attach an ImplicitReturn marker so it behaves correctly in the AST/printing.
                //
                // We also need to clear specific formatting prefixes:
                // The 'return' keyword often has a trailing space (e.g. "return 1").
                // If we remove "return", we likely want to strip that prefix from the expression itself
                // so it doesn't end up with weird leading whitespace.
                val newExpression =
                    lastStat.expression!!.withPrefix<J>(Space.EMPTY) as org.openrewrite.java.tree.Expression

                var markers = lastStat.markers
                markers = markers.addIfAbsent(ImplicitReturn(Tree.randomId()))

                // Replace the return statement so it keeps the expression and uses the implicit-return marker.
                val newLastStat = lastStat.withMarkers(markers).withExpression(newExpression)

                val newStatements = statements.toMutableList()
                newStatements[lastIdx] = newLastStat

                return block.withStatements(newStatements)
            }

            return block
        }
    }
}
