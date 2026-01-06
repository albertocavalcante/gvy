package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryReturnKeyword] recipe.
 *
 * CodeNarc Rule: UnnecessaryReturnKeyword
 *
 * Checks for explicit return statements that can be removed.
 * In Groovy, the last expression in a block is implicitly returned.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessaryreturnkeyword">CodeNarc Rule</a>
 */
class RemoveUnnecessaryReturnKeywordTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryReturnKeyword())
    }

    @Test
    fun `removes return from method`() = rewriteRun(
        groovy(
            """
            String hello() {
                return "world"
            }
            """,
            """
            String hello() {
                "world"
            }
            """,
        ),
    )

    @Test
    fun `removes return from closure`() = rewriteRun(
        groovy(
            """
            def c = {
                return "foo"
            }
            """,
            """
            def c = {
                "foo"
            }
            """,
        ),
    )

    @Test
    fun `preserves early return`() = rewriteRun(
        groovy(
            """
            String foo(boolean b) {
                if (b) {
                    return "early"
                }
                return "late"
            }
            """,
            """
            String foo(boolean b) {
                if (b) {
                    return "early"
                }
                "late"
            }
            """,
        ),
    )

    @Test
    fun `preserves void return`() = rewriteRun(
        groovy(
            """
            void doSomething() {
                println "doing"
                return
            }
            """,
        ),
    )

    @Test
    fun `removes return with complex expression`() = rewriteRun(
        groovy(
            """
            def foo(a, b) {
                println "calculating"
                return a + b * 2
            }
            """,
            """
            def foo(a, b) {
                println "calculating"
                a + b * 2
            }
            """,
        ),
    )
}
