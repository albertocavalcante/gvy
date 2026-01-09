package com.github.albertocavalcante.refactor.codenarc.formatting

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [AddSpaceBeforeOpeningBrace] recipe.
 *
 * CodeNarc Rule: SpaceBeforeOpeningBrace
 * - Message: "The opening brace for X is not preceded by a space or whitespace"
 * - Priority: 3
 *
 * @see <a href="https://codenarc.org/codenarc-rules-formatting.html#spacebeforeopeningbrace">CodeNarc Rule</a>
 */
class AddSpaceBeforeOpeningBraceTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(AddSpaceBeforeOpeningBrace())
    }

    // Positive tests - should add space:

    @Test
    fun `adds space before brace in method declaration`() = rewriteRun(
        groovy(
            """
            def foo(){}
            """,
            """
            def foo() {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in closure`() = rewriteRun(
        groovy(
            """
            list.each{}
            """,
            """
            list.each {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in if statement`() = rewriteRun(
        groovy(
            """
            if (x){}
            """,
            """
            if (x) {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in class declaration`() = rewriteRun(
        groovy(
            """
            class Foo{}
            """,
            """
            class Foo {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in for loop`() = rewriteRun(
        groovy(
            """
            for (i in 1..10){}
            """,
            """
            for (i in 1..10) {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in while loop`() = rewriteRun(
        groovy(
            """
            while (true){}
            """,
            """
            while (true) {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in try-catch`() = rewriteRun(
        groovy(
            """
            try{}catch (Exception e){}
            """,
            """
            try {}catch (Exception e) {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in interface declaration`() = rewriteRun(
        groovy(
            """
            interface Foo{}
            """,
            """
            interface Foo {}
            """,
        ),
    )

    // Negative tests - should NOT add space:

    @Test
    fun `no change when space already exists`() = rewriteRun(
        groovy(
            """
            def foo() {}
            class Bar {}
            if (x) {}
            """,
        ),
    )

    @Test
    fun `no change when newline before brace`() = rewriteRun(
        groovy(
            """
            class Foo
            {
            }
            """,
        ),
    )

    @Test
    fun `no change when multiple spaces exist`() = rewriteRun(
        groovy(
            """
            def foo()  {}
            """,
        ),
    )
}
