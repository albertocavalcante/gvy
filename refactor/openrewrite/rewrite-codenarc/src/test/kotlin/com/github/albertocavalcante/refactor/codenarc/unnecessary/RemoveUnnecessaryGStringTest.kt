package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryGString] recipe.
 *
 * CodeNarc Rule: UnnecessaryGString
 * - Message: "The GString does not contain any expressions. Use a regular String instead."
 * - Priority: 3
 *
 * Note: OpenRewrite's Groovy parser optimizes simple `"string"` literals without
 * interpolation to `J.Literal` nodes rather than `G.GString` nodes. This recipe
 * therefore only handles actual GString objects that have been created but have
 * no expressions.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarygstring">CodeNarc Rule</a>
 */
class RemoveUnnecessaryGStringTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryGString())
    }

    // Negative tests - should NOT convert:

    @Test
    fun `preserves GString with expressions`() = rewriteRun(
        groovy(
            """
            def name = "World"
            def str = "Hello ${'$'}{name}"
            """,
        ),
    )

    @Test
    fun `preserves GString with simple variable`() = rewriteRun(
        groovy(
            """
            def name = "World"
            def str = "Hello ${'$'}name"
            """,
        ),
    )

    @Test
    fun `no change for single-quoted strings`() = rewriteRun(
        groovy(
            """
            def str = 'hello'
            """,
        ),
    )

    @Test
    fun `preserves GStrings with internal single quotes`() = rewriteRun(
        groovy(
            """
            def str = "it's a test"
            """,
        ),
    )
}
