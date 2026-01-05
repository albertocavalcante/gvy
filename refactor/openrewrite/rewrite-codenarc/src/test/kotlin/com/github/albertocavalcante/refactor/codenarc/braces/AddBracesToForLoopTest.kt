package com.github.albertocavalcante.refactor.codenarc.braces

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [AddBracesToForLoop] recipe.
 *
 * CodeNarc Rule: ForStatementBraces
 * - Message: "for statements should use braces"
 * - Priority: 2
 *
 * @see <a href="https://codenarc.org/codenarc-rules-braces.html#forstatementbraces">CodeNarc Rule</a>
 */
class AddBracesToForLoopTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(AddBracesToForLoop())
    }

    @Test
    fun `adds braces to single-line for loop`() = rewriteRun(
        groovy(
            """
            for (int i = 0; i < 10; i++) println(i)
            """,
            """
            for (int i = 0; i < 10; i++) {
                println(i)
            }
            """,
        ),
    )

    @Test
    fun `adds braces to for-each loop`() = rewriteRun(
        groovy(
            """
            for (item in list) println(item)
            """,
            """
            for (item in list) {
                println(item)
            }
            """,
        ),
    )

    // Negative test
    @Test
    fun `no change when braces already exist`() = rewriteRun(
        groovy(
            """
            for (int i = 0; i < 10; i++) {
                println(i)
            }
            """,
        ),
    )
}
