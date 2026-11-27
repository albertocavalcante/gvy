// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [SimplifyExplicitArrayListInstantiation] recipe.
 *
 * CodeNarc Rule: ExplicitArrayListInstantiation
 *
 * Checks for explicit instantiation of ArrayList.
 * In Groovy, it is idiomatic to use the literal syntax `[]` instead.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-groovyism.html#explicitarraylistinstantiation">CodeNarc Rule</a>
 */
class SimplifyExplicitArrayListInstantiationTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(SimplifyExplicitArrayListInstantiation())
    }

    @Test
    fun `converts new ArrayList to list literal`(): Unit = rewriteRun(
        groovy(
            """
            def list = new ArrayList()
            """,
            """
            def list = []
            """,
        ),
    )

    @Test
    fun `converts fully qualified java util ArrayList`(): Unit = rewriteRun(
        groovy(
            """
            def list = new java.util.ArrayList()
            """,
            """
            def list = []
            """,
        ),
    )

    @Test
    fun `converts new ArrayList with generic type`(): Unit = rewriteRun(
        groovy(
            """
            def list = new ArrayList<String>()
            """,
            """
            def list = []
            """,
        ),
    )

    @Test
    fun `preserves ArrayList with initial capacity`(): Unit = rewriteRun(
        groovy(
            """
            def list = new ArrayList(10)
            """,
        ),
    )

    @Test
    fun `preserves ArrayList with collection argument`(): Unit = rewriteRun(
        groovy(
            """
            def other = [1, 2]
            def list = new ArrayList(other)
            """,
        ),
    )
}
