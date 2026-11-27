// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * Tests for FixBrokenNullCheck recipe.
 *
 * Verifies that potentially buggy null checks are fixed.
 * Checks for cases where || is used instead of && in null checks.
 */
class FixBrokenNullCheckTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(FixBrokenNullCheck())
    }

    @Test
    fun `fixes broken null check with property access`(): Unit = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x != null || x.prop) {
                        println "safe"
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x != null && x.prop) {
                        println "safe"
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `fixes broken null check with method call`(): Unit = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x != null || x.method()) {
                        println "safe"
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x != null && x.method()) {
                        println "safe"
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `fixes broken null check with reverse order`(): Unit = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x != null || x instanceof String) {
                        println "safe"
                    }
                }
            }
            """,
            """
            class Example {
                def foo(x) {
                    if (x != null && x instanceof String) {
                        println "safe"
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves correct null check`(): Unit = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x) {
                    if (x != null && x.prop) {
                        println "safe"
                    }
                }
            }
            """,
        ),
    )

    @Test
    fun `preserves valid OR check`(): Unit = rewriteRun(
        groovy(
            """
            class Example {
                def foo(x, y) {
                    if (x != null || y != null) {
                        println "one is not null"
                    }
                }
            }
            """,
        ),
    )
}
