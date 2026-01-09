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

    // ========================================================================
    // Method Declarations
    // ========================================================================

    @Test
    fun `adds space before brace in method declaration`() = rewriteRun(
        groovy(
            // Before: no space between ) and {
            """
            def foo(){}
            """,
            // After: space added
            """
            def foo() {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in method with parameters`() = rewriteRun(
        groovy(
            """
            def bar(String name, int count){}
            """,
            """
            def bar(String name, int count) {}
            """,
        ),
    )

    // ========================================================================
    // Class/Interface Declarations
    // ========================================================================

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

    @Test
    fun `adds space before brace in class with extends`() = rewriteRun(
        groovy(
            """
            class Bar extends Foo{}
            """,
            """
            class Bar extends Foo {}
            """,
        ),
    )

    // ========================================================================
    // Control Structures - if/else
    // ========================================================================

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
    fun `adds space before brace in else block`() = rewriteRun(
        groovy(
            // Both if and else blocks need space
            """
            if (x){} else{}
            """,
            """
            if (x) {} else {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in else-if chain`() = rewriteRun(
        groovy(
            """
            if (a){} else if (b){} else{}
            """,
            """
            if (a) {} else if (b) {} else {}
            """,
        ),
    )

    // ========================================================================
    // Control Structures - loops
    // ========================================================================

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

    // ========================================================================
    // Control Structures - try/catch/finally
    // ========================================================================

    @Test
    fun `adds space before brace in try block`() = rewriteRun(
        groovy(
            """
            try{} catch (Exception e) {}
            """,
            """
            try {} catch (Exception e) {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in catch block`() = rewriteRun(
        groovy(
            """
            try {} catch (Exception e){}
            """,
            """
            try {} catch (Exception e) {}
            """,
        ),
    )

    @Test
    fun `adds space before brace in finally block`() = rewriteRun(
        groovy(
            """
            try {} finally{}
            """,
            """
            try {} finally {}
            """,
        ),
    )

    @Test
    fun `adds space in complete try-catch-finally`() = rewriteRun(
        groovy(
            // All three blocks missing space
            """
            try{} catch (Exception e){} finally{}
            """,
            """
            try {} catch (Exception e) {} finally {}
            """,
        ),
    )

    // ========================================================================
    // Closures - Trailing (with OmitParentheses marker)
    // ========================================================================

    @Test
    fun `adds space before brace in trailing closure`() = rewriteRun(
        groovy(
            // Trailing closure: closure comes after method name without parens
            // e.g., list.each{} instead of list.each({})
            """
            list.each{}
            """,
            """
            list.each {}
            """,
        ),
    )

    @Test
    fun `adds space in chained trailing closures`() = rewriteRun(
        groovy(
            """
            list.findAll{}.collect{}
            """,
            """
            list.findAll {}.collect {}
            """,
        ),
    )

    // ========================================================================
    // Closures - Non-trailing (spacing from parent context)
    // NOTE: These rely on parent context (=, (, etc.) for spacing
    // ========================================================================

    @Test
    fun `no change for closure assigned to variable - spacing from equals`() = rewriteRun(
        groovy(
            // The = operator provides spacing before {
            // This is correct: "= {" has space from assignment
            """
            def fn = {}
            """,
        ),
    )

    @Test
    fun `no change for closure as method argument - spacing from paren`() = rewriteRun(
        groovy(
            // The ( provides the context, closure is inside parens
            // OpenRewrite limitation: can't add space inside parens before closure
            """
            list.collect({})
            """,
        ),
    )

    // ========================================================================
    // Multiple Issues - verifies all are fixed in single pass
    // ========================================================================

    @Test
    fun `fixes multiple missing spaces in same file`() = rewriteRun(
        groovy(
            """
            class Foo{
                def bar(){}
                def baz(){ if (true){} }
            }
            """,
            """
            class Foo {
                def bar() {}
                def baz() { if (true) {} }
            }
            """,
        ),
    )

    // ========================================================================
    // Negative Tests - should NOT modify
    // ========================================================================

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
            // Allman/BSD style braces on new line - already has whitespace (newline)
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
            // Already has whitespace (multiple spaces) - preserve as-is
            """
            def foo()  {}
            """,
        ),
    )

    @Test
    fun `no change for empty map literal`() = rewriteRun(
        groovy(
            // [:] is a map literal, not a block - should not be modified
            """
            def map = [:]
            """,
        ),
    )

    @Test
    fun `no change for braces in string`() = rewriteRun(
        groovy(
            // Braces inside strings should not be modified
            """
            def s = "hello{world}"
            """,
        ),
    )
}
