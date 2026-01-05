package com.github.albertocavalcante.refactor.codenarc.formatting

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [AddSpaceAfterComma] recipe.
 *
 * CodeNarc Rule: SpaceAfterComma
 * - Message: "The comma is not followed by a space or whitespace"
 * - Priority: 3
 *
 * @see <a href="https://codenarc.org/codenarc-rules-formatting.html#spaceaftercomma">CodeNarc Rule</a>
 */
class AddSpaceAfterCommaTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(AddSpaceAfterComma())
    }

    @Test
    fun `adds space after comma in method arguments`() = rewriteRun(
        groovy(
            """
            println("hello","world")
            """,
            """
            println("hello", "world")
            """,
        ),
    )

    @Test
    fun `adds space after comma in list literal`() = rewriteRun(
        groovy(
            """
            def list = [1,2,3]
            """,
            """
            def list = [1, 2, 3]
            """,
        ),
    )

    @Test
    fun `adds space after comma in map literal`() = rewriteRun(
        groovy(
            """
            def map = [a:1,b:2]
            """,
            """
            def map = [a:1, b:2]
            """,
        ),
    )

    @Test
    fun `adds space after comma in method parameters`() = rewriteRun(
        groovy(
            """
            def foo(String a,String b) {}
            """,
            """
            def foo(String a, String b) {}
            """,
        ),
    )

    // Negative tests - should NOT add space:

    @Test
    fun `no change when space already exists after comma`() = rewriteRun(
        groovy(
            """
            println("hello", "world")
            def list = [1, 2, 3]
            """,
        ),
    )

    @Test
    fun `no change when newline after comma`() = rewriteRun(
        groovy(
            """
            def list = [
                1,
                2,
                3
            ]
            """,
        ),
    )

    @Test
    fun `preserves comma in strings`() = rewriteRun(
        groovy(
            """
            def str = "hello,world"
            """,
        ),
    )
}
