package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [SimplifyTernaryToElvis] recipe.
 *
 * CodeNarc Rule: SimplifyTernaryToElvis
 *
 * Checks for ternary expressions that can be simplified to the Elvis operator.
 * e.g. x ? x : y -> x ?: y
 *
 * @see <a href="https://codenarc.org/codenarc-rules-groovyism.html#simplifyternarytoelvis">CodeNarc Rule</a>
 */
class SimplifyTernaryToElvisTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(SimplifyTernaryToElvis())
    }

    @Test
    fun `simplifies basic ternary`() = rewriteRun(
        groovy(
            """
            def x = true
            // Basic case: local variable used in both condition and true part
            def y = x ? x : false
            """,
            """
            def x = true
            // Basic case: local variable used in both condition and true part
            def y = x ?: false
            """,
        ),
    )

    @Test
    fun `simplifies ternary with method call`() = rewriteRun(
        groovy(
            """
            // Method call case: identical method invocations
            // Note: This assumes the method call is side-effect free or the user accepts the risk.
            // The logic checks for structural equality.
            def y = getFoo() ? getFoo() : "default"
            """,
            """
            // Method call case: identical method invocations
            // Note: This assumes the method call is side-effect free or the user accepts the risk.
            // The logic checks for structural equality.
            def y = getFoo() ?: "default"
            """,
        ),
    )

    @Test
    fun `does not simplify distinct expressions`() = rewriteRun(
        groovy(
            """
            // Should not simplify if condition and true part are different
            def y = x ? z : false
            """,
        ),
    )

    @Test
    fun `does not simplify side effects`() = rewriteRun(
        groovy(
            """
            // Should not simplify expressions with obvious side effects (like post-increment)
            // primarily because our areEquivalent logic returns false for unknown types/operations.
            def y = i++ ? i++ : 0
            """,
        ),
    )
}
