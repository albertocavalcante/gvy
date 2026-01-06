package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [SimplifyExplicitHashMapInstantiation] recipe.
 *
 * CodeNarc Rule: ExplicitHashMapInstantiation
 *
 * Checks for explicit instantiation of HashMap and LinkedHashMap.
 * In Groovy, `[:]` creates a LinkedHashMap by default, so both can be simplified.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-groovyism.html#explicithashMapinstantiation">CodeNarc Rule</a>
 */
class SimplifyExplicitHashMapInstantiationTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(SimplifyExplicitHashMapInstantiation())
    }

    @Test
    fun `converts new HashMap to map literal`() = rewriteRun(
        groovy(
            """
            def map = new HashMap()
            """,
            """
            def map = [:]
            """,
        ),
    )

    @Test
    fun `converts new LinkedHashMap to map literal`() = rewriteRun(
        groovy(
            """
            def map = new java.util.LinkedHashMap()
            """,
            """
            def map = [:]
            """,
        ),
    )

    @Test
    fun `converts generic HashMap`() = rewriteRun(
        groovy(
            """
            def map = new HashMap<String, String>()
            """,
            """
            def map = [:]
            """,
        ),
    )

    @Test
    fun `preserves with initial capacity`() = rewriteRun(
        groovy(
            """
            def map = new HashMap(10)
            """,
        ),
    )

    @Test
    fun `preserves with map argument`() = rewriteRun(
        groovy(
            """
            def other = [a: 1]
            def map = new HashMap(other)
            """,
        ),
    )
}
