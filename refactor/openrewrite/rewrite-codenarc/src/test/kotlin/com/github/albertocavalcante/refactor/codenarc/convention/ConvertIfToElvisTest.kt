package com.github.albertocavalcante.refactor.codenarc.convention

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

class ConvertIfToElvisTest : RewriteTest {
    override fun defaults(spec: RecipeSpec) {
        spec.recipe(ConvertIfToElvis())
    }

    @Disabled("Upstream OpenRewrite bug: https://github.com/openrewrite/rewrite/issues/6482")
    @Test
    fun `converts if not x then assign y`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x, y) {
                    if (!x) {
                        x = y
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x, y) {
                    x = x ?: y
                }
            }
            """,
        ),
    )

    @Disabled("Upstream OpenRewrite bug: https://github.com/openrewrite/rewrite/issues/6482")
    @Test
    fun `converts if x is null then assign default`() = rewriteRun(
        groovy(
            """
            class Example {
                def foo() {
                    def x
                    if (x == null) {
                        x = "default"
                    }
                }
            }
            """,
            """

            
            class Example {
                def foo() {
                    def x
                    x = x ?: "default"
                }
            }
            """,
        ),
    )

    @Disabled("Upstream OpenRewrite bug: https://github.com/openrewrite/rewrite/issues/6482")
    @Test
    fun `converts property assignment`() = rewriteRun(
        groovy(
            """
            class Example {
                String name
                def init() {
                    if (!name) {
                        name = "unknown"
                    }
                }
            }
            """,
            """
            class Example {
                String name
                def init() {
                    name = name ?: "unknown"
                }
            }
            """,
        ),
    )

    @Test
    fun `does not convert if x`() = rewriteRun(
        groovy(
            """
            if (x) {
                x = y
            }
            """,
        ),
    )

    @Test
    fun `preserves if when assignment target is different`() = rewriteRun(
        groovy(
            """
            if (!x) {
                y = z
            }
            """,
        ),
    )

    @Test
    fun `preserves complex if body`() = rewriteRun(
        groovy(
            """
            if (!x) {
                x = y
                println x
            }
            """,
        ),
    )

    @Disabled("Upstream OpenRewrite bug: https://github.com/openrewrite/rewrite/issues/6482")
    @Test
    fun `converts non-braced if body`() = rewriteRun(
        groovy(
            """
            if (!x) x = y
            """,
            """
            x = x ?: y
            """,
        ),
    )
}
