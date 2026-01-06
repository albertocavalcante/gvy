package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [SimplifyExplicitHashSetInstantiation] recipe.
 *
 * CodeNarc Rule: ExplicitHashSetInstantiation
 *
 * Checks for explicit instantiation of HashSet.
 * In Groovy, `[] as Set` is the idiomatic way to create an empty Set.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-groovyism.html#explicithashsetinstantiation">CodeNarc Rule</a>
 */
class SimplifyExplicitHashSetInstantiationTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(SimplifyExplicitHashSetInstantiation())
    }

    @Test
    fun `converts new HashSet to set cast`() = rewriteRun(
        groovy(
            """
            def set = new HashSet()
            """,
            """
            def set = [] as Set
            """,
        ),
    )

    @Test
    fun `converts fully qualified HashSet`() = rewriteRun(
        groovy(
            """
            def set = new java.util.HashSet()
            """,
            """
            def set = [] as Set
            """,
        ),
    )

    @Test
    fun `converts generic HashSet`() = rewriteRun(
        groovy(
            """
            def set = new HashSet<String>()
            """,
            """
            def set = [] as Set
            """,
        ),
    )

    @Test
    fun `preserves HashSet with initial capacity`() = rewriteRun(
        groovy(
            """
            def set = new HashSet(10)
            """,
        ),
    )

    @Test
    fun `preserves HashSet with collection argument`() = rewriteRun(
        groovy(
            """
            def list = [1, 2]
            def set = new HashSet(list)
            """,
        ),
    )
}
