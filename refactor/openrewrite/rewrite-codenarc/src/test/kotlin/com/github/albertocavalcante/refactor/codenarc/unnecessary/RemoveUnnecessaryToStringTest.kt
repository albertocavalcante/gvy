package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * Tests for RemoveUnnecessaryToString recipe.
 *
 * Verifies that redundant toString() calls are removed in various contexts:
 * - On String literals
 * - When concatenating with Strings
 */
class RemoveUnnecessaryToStringTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryToString())
    }

    @Test
    fun `removes toString on String literal`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo() {
                    def s = "hello".toString()
                }
            }
            """,
            """
            class Example {
                def foo() {
                    def s = "hello"
                }
            }
            """,
        ),
    )

    @Test
    fun `removes toString when concatenating with String`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    def s = "prefix" + x.toString()
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    def s = "prefix" + x
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves toString on non-String receiver when not in String context`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    println x.toString()
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves toString with arguments`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    def s = x.toString(16)
                }
            }
            """,
        ),
    )
}
