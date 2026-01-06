package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [MoveClosureAsLastMethodParameter] recipe.
 *
 * CodeNarc Rule: ClosureAsLastMethodParameter
 *
 * Checks for a closure that is the last parameter of a method call, and moves it outside the parentheses.
 * e.g. list.each({ println it }) -> list.each { println it }
 *
 * @see <a href="https://codenarc.org/codenarc-rules-groovyism.html#closureaslastmethodparameter">CodeNarc Rule</a>
 */
class MoveClosureAsLastMethodParameterTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(MoveClosureAsLastMethodParameter())
    }

    @Test
    fun `moves closure outside for each`() = rewriteRun(
        groovy(
            """
            [1, 2].each({ println it })
            """,
            """
            [1, 2].each { println it }
            """,
        ),
    )

    @Test
    fun `moves closure for mixed args`() = rewriteRun(
        groovy(
            """
            def foo(int x, Closure c) {}
            foo(1, { println it })
            """,
            """
            def foo(int x, Closure c) {}
            foo(1) { println it }
            """,
        ),
    )

    @Test
    fun `handles multiline closure`() = rewriteRun(
        groovy(
            """
            [1].each({ 
                println it
                println it
            })
            """,
            """
            [1].each { 
                println it
                println it
            }
            """,
        ),
    )

    @Test
    fun `no change when already outside`() = rewriteRun(
        groovy(
            """
            [1].each { println it }
            """,
        ),
    )

    @Test
    fun `preserves when closure is not last`() = rewriteRun(
        groovy(
            """
            def foo(Closure c, int x) {}
            foo({ println it }, 1)
            """,
        ),
    )
}
