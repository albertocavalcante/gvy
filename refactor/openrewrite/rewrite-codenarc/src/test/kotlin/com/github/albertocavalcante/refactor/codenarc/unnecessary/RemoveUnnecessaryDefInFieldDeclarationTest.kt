package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryDefInFieldDeclaration] recipe.
 *
 * CodeNarc Rule: UnnecessaryDefInFieldDeclaration
 *
 * In Groovy, def is unnecessary when explicit type or modifier is present.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarydefinfielddeclaration">CodeNarc Rule</a>
 */
class RemoveUnnecessaryDefInFieldDeclarationTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryDefInFieldDeclaration())
    }

    @Test
    fun `removes def with static modifier`() = rewriteRun(
        groovy(
            """
            class Foo {
                def static x = 1
            }
            """,
            """
            class Foo {
                static x = 1
            }
            """,
        ),
    )

    @Test
    fun `removes def with visibility modifier`() = rewriteRun(
        groovy(
            """
            class Foo {
                def private y = 2
            }
            """,
            """
            class Foo {
                private y = 2
            }
            """,
        ),
    )

    @Test
    fun `removes def with explicit type`() = rewriteRun(
        groovy(
            """
            class Foo {
                def String name = "test"
            }
            """,
            """
            class Foo {
                String name = "test"
            }
            """,
        ),
    )

    @Test
    fun `preserves standalone def`() = rewriteRun(
        groovy(
            """
            class Foo {
                def x = 1
            }
            """,
        ),
    )
}
