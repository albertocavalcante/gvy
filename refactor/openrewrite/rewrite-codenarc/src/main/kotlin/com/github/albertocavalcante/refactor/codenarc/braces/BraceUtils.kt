package com.github.albertocavalcante.refactor.codenarc.braces

import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Space
import org.openrewrite.java.tree.Statement
import org.openrewrite.marker.Markers
import java.util.UUID

/**
 * Common utility functions for brace-related recipes.
 */
object BraceUtils {

    /**
     * Wrap a statement in a block with proper formatting.
     * Used by AddBracesToIfStatement, AddBracesToForLoop, and AddBracesToWhileLoop.
     */
    fun wrapInBlock(statement: Statement): J.Block {
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
