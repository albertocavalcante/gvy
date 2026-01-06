package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryPublicModifier] recipe.
 *
 * CodeNarc Rule: UnnecessaryPublicModifier
 *
 * In Groovy, classes, methods, and constructors are public by default.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarypublicmodifier">CodeNarc Rule</a>
 */
class RemoveUnnecessaryPublicModifierTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryPublicModifier())
    }

    @Test
    fun `removes public from class`() = rewriteRun(
        groovy(
            """
            public class Foo {}
            """,
            """
            class Foo {}
            """,
        ),
    )

    @Test
    fun `removes public from method`() = rewriteRun(
        groovy(
            """
            class Foo {
                public void bar() {}
            }
            """,
            """
            class Foo {
                void bar() {}
            }
            """,
        ),
    )

    @Test
    fun `removes public from constructor`() = rewriteRun(
        groovy(
            """
            class Foo {
                public Foo() {}
            }
            """,
            """
            class Foo {
                Foo() {}
            }
            """,
        ),
    )

    @Test
    fun `preserves other modifiers`() = rewriteRun(
        groovy(
            """
            class Foo {
                public static void bar() {}
            }
            """,
            """
            class Foo {
                static void bar() {}
            }
            """,
        ),
    )

    @Test
    fun `preserves private modifier`() = rewriteRun(
        groovy(
            """
            class Foo {
                private void bar() {}
            }
            """,
        ),
    )

    @Test
    fun `preserves protected modifier`() = rewriteRun(
        groovy(
            """
            class Foo {
                protected void bar() {}
            }
            """,
        ),
    )
}
