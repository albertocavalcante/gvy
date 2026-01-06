package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * Tests for RemoveUnnecessaryNullCheckBeforeInstanceOf recipe.
 *
 * Verifies that redundant null checks before instanceof are removed.
 * The instanceof operator already returns false for null values.
 */
class RemoveUnnecessaryNullCheckBeforeInstanceOfTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryNullCheckBeforeInstanceOf())
    }

    @Test
    fun `removes null check before instanceof`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x != null && x instanceof String) {
                        println x
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x instanceof String) {
                        println x
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `removes null check after instanceof`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x instanceof String && x != null) {
                        println x
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x instanceof String) {
                        println x
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves null check with different variable`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x, y) {
                    if (x != null && y instanceof String) {
                        println y
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves null check without instanceof`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x != null && x.length() > 0) {
                        println x
                    }
                }
            }
            """,
        ),
    )
}
