package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Statement

/**
 * Recipe to remove unnecessary else statements.
 *
 * This aligns with CodeNarc rule: UnnecessaryElseStatement
 *
 * When an if statement block ends with a return statement, the else is unnecessary
 * because the code would never reach that point anyway.
 *
 * e.g.
 * ```
 * if (x) { return 1 } else { return 2 }
 * ```
 * becomes:
 * ```
 * if (x) { return 1 }
 * return 2
 * ```
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessaryelsestatement">CodeNarc Rule</a>
 */
class RemoveUnnecessaryElseStatement : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary else statement"

    override fun getDescription(): String = "Removes else blocks when the if block ends with a return statement, " +
        "since the else is unreachable after a return."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {

        override fun visitBlock(block: J.Block, ctx: ExecutionContext): J.Block {
            var b = super.visitBlock(block, ctx)

            // Process each statement to find if-else patterns
            val newStatements = mutableListOf<Statement>()
            var changed = false

            for (stmt in b.statements) {
                if (stmt is J.If) {
                    val (ifStatement, extractedStatements, wasChanged) = processIf(stmt)
                    if (wasChanged) {
                        changed = true
                        newStatements.add(ifStatement)
                        newStatements.addAll(extractedStatements)
                    } else {
                        newStatements.add(stmt)
                    }
                } else {
                    newStatements.add(stmt)
                }
            }

            return if (changed) b.withStatements(newStatements) else b
        }

        /**
         * Returns Triple of: (modified if statement, extracted else statements, whether a change was made)
         */
        private fun processIf(ifStmt: J.If): Triple<J.If, List<Statement>, Boolean> {
            val elsePart = ifStmt.elsePart ?: return Triple(ifStmt, emptyList(), false)
            val elseBody = elsePart.body

            // Don't transform else-if chains
            if (elseBody is J.If) {
                return Triple(ifStmt, emptyList(), false)
            }

            // Check if the if block ends with a return
            if (!blockEndsWithReturn(ifStmt.thenPart)) {
                return Triple(ifStmt, emptyList(), false)
            }

            // Extract statements from else block
            // Use the if statement's prefix (which includes newline + proper indentation)
            val ifPrefix = ifStmt.prefix

            val extractedStatements = when (elseBody) {
                is J.Block -> elseBody.statements.map { stmt ->
                    // Use the if statement's prefix for proper indentation
                    stmt.withPrefix<Statement>(ifPrefix)
                }

                else -> listOf((elseBody as Statement).withPrefix<Statement>(ifPrefix))
            }

            // Remove the else part from the if
            val newIf = ifStmt.withElsePart(null)

            return Triple(newIf, extractedStatements, true)
        }

        private fun blockEndsWithReturn(statement: Statement): Boolean = when (statement) {
            is J.Block -> {
                val lastStmt = statement.statements.lastOrNull()
                lastStmt is J.Return
            }

            is J.Return -> true
            else -> false
        }
    }
}
