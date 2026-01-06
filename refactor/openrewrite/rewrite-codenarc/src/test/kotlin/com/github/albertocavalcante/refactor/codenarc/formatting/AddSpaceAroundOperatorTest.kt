package com.github.albertocavalcante.refactor.codenarc.formatting

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [AddSpaceAroundOperator] recipe.
 *
 * CodeNarc Rule: SpaceAroundOperator
 *
 * Checks that there is at least one space around operators.
 * e.g. x=y -> x = y
 *
 * @see <a href="https://codenarc.org/codenarc-rules-formatting.html#spacearoundoperator">CodeNarc Rule</a>
 */
class AddSpaceAroundOperatorTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(AddSpaceAroundOperator())
    }

    @Test
    fun `adds space around assignment`() = rewriteRun(
        groovy(
            """
            def x=1
            """,
            """
            def x = 1
            """,
        ),
    )

    @Test
    fun `adds space around binary operator`() = rewriteRun(
        groovy(
            """
            int a=1+2
            """,
            """
            int a = 1 + 2
            """,
        ),
    )

    @Test
    fun `adds space around assignment operation`() = rewriteRun(
        groovy(
            """
            int a = 1
            a+=2
            """,
            """
            int a = 1
            a += 2
            """,
        ),
    )

    @Test
    fun `preserves existing spaces`() = rewriteRun(
        groovy(
            """
            int a = 1 + 2
            """,
        ),
    )

    @Test
    fun `handles ternary operator`() = rewriteRun(
        groovy(
            """
            def a = true?1:0
            """,
            """
            def a = true ? 1 : 0
            """,
        ),
    )
}
