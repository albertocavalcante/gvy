// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.refactor.codenarc.convention

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

class UninvertIfElseTest : RewriteTest {
    override fun defaults(spec: RecipeSpec) {
        spec.recipe(UninvertIfElse())
    }

    @Test
    fun `uninvert if else with not operator`(): Unit = rewriteRun(
        groovy(
            """
            if (!x) {
                println "false"
            } else {
                println "true"
            }
            """,
            """
            if (x) {
                println "true"
            } else {
                println "false"
            }
            """,
        ),
    )

    @Test
    fun `uninvert if else with not equal operator`(): Unit = rewriteRun(
        groovy(
            """
            if (x != y) {
                a()
            } else {
                b()
            }
            """,
            """
            if (x == y) {
                b()
            } else {
                a()
            }
            """,
        ),
    )

    @Test
    fun `does not change if no else`(): Unit = rewriteRun(
        groovy(
            """
            if (!x) {
                println "false"
            }
            """,
        ),
    )

    @Test
    fun `uninvert complex negation`(): Unit = rewriteRun(
        groovy(
            """
            if (!(x && y)) {
                a()
            } else {
                b()
            }
            """,
            """
            if ((x && y)) {
                b()
            } else {
                a()
            }
            """,
        ),
    )
}
