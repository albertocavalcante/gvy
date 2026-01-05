package com.github.albertocavalcante.refactor.codenarc.braces

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [AddBracesToIfStatement] recipe.
 *
 * CodeNarc Rule: IfStatementBraces
 * - Message: "if statements should use braces"
 * - Priority: 2
 *
 * @see <a href="https://codenarc.org/codenarc-rules-braces.html#ifstatementbraces">CodeNarc Rule</a>
 */
class AddBracesToIfStatementTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(AddBracesToIfStatement())
    }

    @Test
    fun `adds braces to single-line if statement`() = rewriteRun(
        groovy(
            """
            if (true) println("hello")
            """,
            """
            if (true) {
                println("hello")
            }
            """,
        ),
    )

    @Test
    fun `adds braces to if-else statement`() = rewriteRun(
        groovy(
            """
            if (true) println("yes")
            else println("no")
            """,
            """
            if (true) {
                println("yes")
            }
            else {
                println("no")
            }
            """,
        ),
    )

    @Test
    fun `adds braces to if with existing else block`() = rewriteRun(
        groovy(
            """
            if (true) println("yes")
            else {
                println("no")
            }
            """,
            """
            if (true) {
                println("yes")
            }
            else {
                println("no")
            }
            """,
        ),
    )

    // Negative tests - should NOT add braces:

    @Test
    fun `no change when braces already exist`() = rewriteRun(
        groovy(
            """
            if (true) {
                println("hello")
            }
            """,
        ),
    )

    @Test
    fun `no change for complete if-else with braces`() = rewriteRun(
        groovy(
            """
            if (true) {
                println("yes")
            } else {
                println("no")
            }
            """,
        ),
    )
}
