package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * Tests for RemoveUnnecessaryElseStatement recipe.
 *
 * Verifies that else blocks are removed when the if block always returns.
 */
class RemoveUnnecessaryElseStatementTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryElseStatement())
    }

    @Test
    fun `removes else when if returns`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x) {
                        return 1
                    } else {
                        return 2
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x) {
                        return 1
                    }
                    return 2
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves else when if does not return`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x) {
                        println "yes"
                    } else {
                        println "no"
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `corrects indentation for multi-statement else`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x) {
                        return 1
                    } else {
                        def a = 1
                        def b = 2
                        println a + b
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x) {
                        return 1
                    }
                    def a = 1
                    def b = 2
                    println a + b
                }
            }
            """,
        ),
    )
}
