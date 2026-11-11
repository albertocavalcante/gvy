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
    fun `preserves identifier reads without binding and truthiness proof`() = rewriteRun(
        groovy(
            """
            def x = true
            // Basic case: local variable used in both condition and true part
            def y = x ? x : false
            """,
        ),
    )

    @Test
    fun `simplifies a repeated literal`() = rewriteRun(
        groovy("def result = true ? true : false", "def result = true ?: false"),
    )

    @Test
    fun `preserves repeated method invocations without proof of purity`() = rewriteRun(
        groovy(
            """
            def getFoo() { "value" }
            def y = getFoo() ? getFoo() : "default"
            """,
        ),
    )

    @Test
    fun `preserves comments attached to the repeated expression`() = rewriteRun(
        groovy(
            """
            def x = 1
            def y = x ? /* keep this explanation */ x : 0
            """,
        ),
    )

    @Test
    fun `preserves repeated calls that mutate state`() = rewriteRun(
        groovy(
            """
            def calls = 0
            def next = { ++calls }
            def result = next() ? next() : 0
            assert calls == 2
            assert result == 2
            """,
        ),
    )

    @Test
    fun `preserves implicit property getter reads that mutate state`() = rewriteRun(
        groovy(
            """
            class A {
                int calls = 0
                def getValue() { ++calls }
                def check() {
                    def result = value ? value : 0
                    assert calls == 2
                    assert result == 2
                }
            }
            new A().check()
            """,
        ),
    )

    @Test
    fun `preserves unbound dynamic property reads`() = rewriteRun(
        groovy("def result = value ? value : 0"),
    )

    @Test
    fun `preserves a local binding changed by Groovy truthiness`() = rewriteRun(
        groovy(
            """
            class Box {
                Closure change
                boolean asBoolean() { change(); true }
            }
            def x = new Box()
            x.change = { x = 'changed' }
            def result = x ? x : null
            assert result == 'changed'
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
