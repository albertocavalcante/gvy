package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryDotClass] recipe.
 *
 * CodeNarc Rule: UnnecessaryDotClass
 * - Message: "The .class identifier is unnecessary"
 * - Priority: 3
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarydotclass">CodeNarc Rule</a>
 */
class RemoveUnnecessaryDotClassTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryDotClass())
    }

    @Test
    fun `removes dot class from String`() = rewriteRun(
        groovy(
            """
            def x = String.class
            """,
            """
            def x = String
            """,
        ),
    )

    @Test
    fun `removes dot class from custom type`() = rewriteRun(
        groovy(
            """
            def type = MyCustomClass.class
            """,
            """
            def type = MyCustomClass
            """,
        ),
    )

    @Test
    fun `removes dot class in method argument`() = rewriteRun(
        groovy(
            """
            process(String.class)
            """,
            """
            process(String)
            """,
        ),
    )

    // Negative tests
    @Test
    fun `no change when class is accessed as property`() = rewriteRun(
        groovy(
            """
            def c = obj.class
            """,
        ),
    )

    @Test
    fun `no change when already without dot class`() = rewriteRun(
        groovy(
            """
            def x = String
            """,
        ),
    )
}
