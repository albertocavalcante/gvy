package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryTernaryExpression] recipe.
 *
 * CodeNarc Rule: UnnecessaryTernaryExpression
 * - Message: "The ternary expression is unnecessary"
 * - Priority: 3
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessaryternaryexpression">CodeNarc Rule</a>
 */
class RemoveUnnecessaryTernaryExpressionTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryTernaryExpression())
    }

    @Test
    fun `simplifies condition to true-false to condition`() = rewriteRun(
        groovy(
            """
            def result = x == 99 ? true : false
            """,
            """
            def result = x == 99
            """,
        ),
    )

    @Test
    fun `simplifies boolean variable ternary`() = rewriteRun(
        groovy(
            """
            def result = flag ? true : false
            """,
            """
            def result = flag
            """,
        ),
    )

    @Test
    fun `negates when false comes first`() = rewriteRun(
        groovy(
            """
            def result = flag ? false : true
            """,
            """
            def result = !flag
            """,
        ),
    )

    @Test
    fun `negates expression when false comes first`() = rewriteRun(
        groovy(
            """
            def result = (a && b) ? false : true
            """,
            """
            def result = !(a && b)
            """,
        ),
    )

    @Test
    fun `handles Boolean constants`() = rewriteRun(
        groovy(
            """
            def result = x < 99 ? Boolean.TRUE : Boolean.FALSE
            """,
            """
            def result = x < 99
            """,
        ),
    )

    @Test
    fun `removes when branches are same constant`() = rewriteRun(
        groovy(
            """
            def result = x ? "same" : "same"
            """,
            """
            def result = "same"
            """,
        ),
    )

    @Test
    fun `removes when branches are same variable`() = rewriteRun(
        groovy(
            """
            def result = x ? y : y
            """,
            """
            def result = y
            """,
        ),
    )

    // Negative tests
    @Test
    fun `preserves meaningful ternary`() = rewriteRun(
        groovy(
            """
            def result = condition ? "yes" : "no"
            """,
        ),
    )
}
