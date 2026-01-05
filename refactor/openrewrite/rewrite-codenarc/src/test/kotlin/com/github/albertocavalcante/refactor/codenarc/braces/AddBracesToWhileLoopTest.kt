package com.github.albertocavalcante.refactor.codenarc.braces

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [AddBracesToWhileLoop] recipe.
 *
 * CodeNarc Rule: WhileStatementBraces
 * - Message: "while statements should use braces"
 * - Priority: 2
 *
 * @see <a href="https://codenarc.org/codenarc-rules-braces.html#whilestatementbraces">CodeNarc Rule</a>
 */
class AddBracesToWhileLoopTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(AddBracesToWhileLoop())
    }

    @Test
    fun `adds braces to single-line while loop`() = rewriteRun(
        groovy(
            """
            while (true) println("waiting")
            """,
            """
            while (true) {
                println("waiting")
            }
            """,
        ),
    )

    // Negative test
    @Test
    fun `no change when braces already exist`() = rewriteRun(
        groovy(
            """
            while (true) {
                println("waiting")
            }
            """,
        ),
    )
}
