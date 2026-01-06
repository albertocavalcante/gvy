package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryDefInMethodDeclaration] recipe.
 *
 * CodeNarc Rule: UnnecessaryDefInMethodDeclaration
 *
 * In Groovy, def is unnecessary when explicit type or modifier is present.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarydefInmethoddeclaration">CodeNarc Rule</a>
 */
class RemoveUnnecessaryDefInMethodDeclarationTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryDefInMethodDeclaration())
    }

    @Test
    fun `removes def with visibility modifier`() = rewriteRun(
        groovy(
            """
            class Foo {
                def private bar() {}
            }
            """,
            """
            class Foo {
                private bar() {}
            }
            """,
        ),
    )

    @Test
    fun `removes def with static modifier`() = rewriteRun(
        groovy(
            """
            class Foo {
                def static baz() {}
            }
            """,
            """
            class Foo {
                static baz() {}
            }
            """,
        ),
    )

    @Test
    fun `removes def with return type`() = rewriteRun(
        groovy(
            """
            class Foo {
                def String getName() { "test" }
            }
            """,
            """
            class Foo {
                String getName() { "test" }
            }
            """,
        ),
    )

    @Test
    fun `preserves standalone def method`() = rewriteRun(
        groovy(
            """
            class Foo {
                def bar() {}
            }
            """,
        ),
    )
}
