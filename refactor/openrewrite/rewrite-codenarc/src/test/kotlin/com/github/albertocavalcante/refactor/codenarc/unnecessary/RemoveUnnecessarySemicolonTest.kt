package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.junit.jupiter.api.Test
import org.openrewrite.groovy.Assertions.groovy
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

/**
 * TDD tests for [RemoveUnnecessarySemicolon] recipe.
 *
 * CodeNarc Rule: UnnecessarySemicolon
 * - Message: "Semicolons as line endings can be removed safely"
 * - Priority: 3
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarysemicolon">CodeNarc Rule</a>
 */
class RemoveUnnecessarySemicolonTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RemoveUnnecessarySemicolon())
    }

    @Test
    fun `removes trailing semicolon from variable declaration`() = rewriteRun(
        groovy(
            """
            String str = 'lelamanul';
            """,
            """
            String str = 'lelamanul'
            """,
        ),
    )

    @Test
    fun `removes trailing semicolon from package declaration`() = rewriteRun(
        groovy(
            """
            package my.company.server;
            
            class MyClass {}
            """,
            """
            package my.company.server
            
            class MyClass {}
            """,
        ),
    )

    @Test
    fun `removes trailing semicolon from import statement`() = rewriteRun(
        groovy(
            """
            import java.util.List;
            
            class MyClass {}
            """,
            """
            import java.util.List
            
            class MyClass {}
            """,
        ),
    )

    @Test
    fun `removes trailing semicolon from field declaration`() = rewriteRun(
        groovy(
            """
            class MyClass {
                String name = 'test';
            }
            """,
            """
            class MyClass {
                String name = 'test'
            }
            """,
        ),
    )

    @Test
    fun `removes trailing semicolon from method call`() = rewriteRun(
        groovy(
            """
            println("hello");
            """,
            """
            println("hello")
            """,
        ),
    )

    @Test
    fun `removes multiple trailing semicolons across file`() = rewriteRun(
        groovy(
            """
            package my.company.server;
            import java.lang.String;
            println("test");
            """,
            """
            package my.company.server
            import java.lang.String
            println("test")
            """,
        ),
    )

    // Negative tests - should NOT remove semicolons:

    @Test
    fun `preserves semicolons in for loop header`() = rewriteRun(
        groovy(
            """
            for (int i = 0; i < 10; i++) {
                println(i)
            }
            """,
        ),
    )

    @Test
    fun `preserves semicolons separating statements on same line`() = rewriteRun(
        groovy(
            """
            def a = 1; def b = 2
            """,
        ),
    )

    @Test
    fun `no change when no trailing semicolons present`() = rewriteRun(
        groovy(
            """
            String str = 'lelamanul'
            println(str)
            """,
        ),
    )
}
