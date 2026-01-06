package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessaryGetter] recipe.
 *
 * CodeNarc Rule: UnnecessaryGetter
 * - Message: "The getName() method call can be rewritten as property access: name"
 * - Priority: 3
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarygetter">CodeNarc Rule</a>
 */
class RemoveUnnecessaryGetterTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessaryGetter())
    }

    @Test
    fun `converts getName to name`() = rewriteRun(
        groovy(
            """
            def x = obj.getName()
            """,
            """
            def x = obj.name
            """,
        ),
    )

    @Test
    fun `converts getSize to size`() = rewriteRun(
        groovy(
            """
            def s = list.getSize()
            """,
            """
            def s = list.size
            """,
        ),
    )

    @Test
    fun `converts isEnabled to enabled`() = rewriteRun(
        groovy(
            """
            if (feature.isEnabled()) {}
            """,
            """
            if (feature.enabled) {}
            """,
        ),
    )

    @Test
    fun `handles chained getter calls`() = rewriteRun(
        groovy(
            """
            def x = obj.getConfig().getName()
            """,
            """
            def x = obj.config.name
            """,
        ),
    )

    // Negative tests - MUST preserve
    @Test
    fun `preserves getClass call`() = rewriteRun(
        groovy(
            """
            def c = obj.getClass()
            """,
        ),
    )

    @Test
    fun `preserves getter with arguments`() = rewriteRun(
        groovy(
            """
            def v = map.getProperty("key")
            """,
        ),
    )

    @Test
    fun `preserves getURL pattern`() = rewriteRun(
        groovy(
            """
            def url = conn.getURL()
            """,
        ),
    )

    @Test
    fun `preserves getter in Spock Mock`() = rewriteRun(
        groovy(
            """
            def mock = Mock(Service) {
                getName() >> "test"
            }
            """,
        ),
    )
}
