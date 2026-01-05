package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.marker.Semicolon
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Statement

/**
 * Recipe to remove unnecessary semicolons from Groovy code.
 *
 * In Groovy, semicolons as line terminators are not required and can be safely removed.
 * This aligns with CodeNarc rule: UnnecessarySemicolon
 *
 * Note: This recipe preserves semicolons that are syntactically required, such as:
 * - Semicolons in for loop headers (e.g., `for (int i = 0; i < 10; i++)`)
 * - Semicolons separating multiple statements on the same line (e.g., `def a = 1; def b = 2`)
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarysemicolon">CodeNarc Rule</a>
 */
class RemoveUnnecessarySemicolon : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary semicolons"

    override fun getDescription(): String = "Semicolons as line terminators are not required in Groovy: remove them. " +
        "This recipe targets trailing semicolons at the end of statements, " +
        "preserving semicolons that separate multiple statements on the same line."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {

            override fun visitCompilationUnit(cu: G.CompilationUnit, ctx: ExecutionContext): G.CompilationUnit {
                var c = super.visitCompilationUnit(cu, ctx)

                // Handle package declaration semicolon
                val pkgDecl = c.padding.packageDeclaration
                if (pkgDecl != null && pkgDecl.markers.findFirst(Semicolon::class.java).isPresent) {
                    c = c.padding.withPackageDeclaration(
                        pkgDecl.withMarkers(pkgDecl.markers.removeByType(Semicolon::class.java)),
                    )
                }

                // Handle statement semicolons (including imports which are top-level statements in Groovy)
                val statements = c.padding.statements
                val updatedStatements = removeSemicolonsFromStatements(statements)

                // Use identity check - only update if we actually created new list
                if (updatedStatements !== statements) {
                    c = c.padding.withStatements(updatedStatements)
                }

                return c
            }

            override fun visitBlock(block: J.Block, ctx: ExecutionContext): J.Block {
                var b = super.visitBlock(block, ctx)

                // Handle statement semicolons within blocks
                val statements = b.padding.statements
                val updatedStatements = removeSemicolonsFromStatements(statements)

                // Use identity check - only update if we actually created new list
                if (updatedStatements !== statements) {
                    b = b.padding.withStatements(updatedStatements)
                }

                return b
            }

            /**
             * Remove semicolons from statements, but only if they are trailing (not statement-separating).
             * Returns the SAME list instance if no changes were made.
             */
            private fun removeSemicolonsFromStatements(
                statements: List<JRightPadded<Statement>>,
            ): List<JRightPadded<Statement>> {
                var changed = false
                val result = statements.mapIndexed { index, stmt ->
                    if (!stmt.markers.findFirst(Semicolon::class.java).isPresent) {
                        // No semicolon to remove
                        stmt
                    } else if (isStatementSeparating(statements, index)) {
                        // Keep semicolons that separate statements on the same line
                        stmt
                    } else {
                        // Remove trailing semicolon
                        changed = true
                        stmt.withMarkers(stmt.markers.removeByType(Semicolon::class.java))
                    }
                }

                return if (changed) result else statements
            }

            /**
             * Check if the semicolon after statement at [index] separates it from another statement on the same line.
             * A semicolon is statement-separating if:
             * 1. There's a next statement
             * 2. Neither the current statement's trailing space NOR the next statement's prefix contains a newline
             */
            private fun isStatementSeparating(statements: List<JRightPadded<Statement>>, index: Int): Boolean {
                // If this is the last statement, it can't be separating
                if (index >= statements.size - 1) {
                    return false
                }

                // Get the space after this statement (JRightPadded.after - between semicolon and next thing)
                val afterSpace = statements[index].after.whitespace

                // Get the prefix space of the next statement
                val nextStmtPrefix = statements[index + 1].element.prefix.whitespace

                // If BOTH spaces have no newline, statements are on same line
                // (newlines can be in either location depending on parsing)
                val combinedSpace = afterSpace + nextStmtPrefix
                return !combinedSpace.contains('\n')
            }
        }
    }
}
