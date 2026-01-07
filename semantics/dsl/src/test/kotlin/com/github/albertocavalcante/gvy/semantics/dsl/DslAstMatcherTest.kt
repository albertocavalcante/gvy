package com.github.albertocavalcante.gvy.semantics.dsl

import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DslAstMatcherTest {

    @Test
    fun `MethodCallMatcher should match simple method call`() {
        // Create a simple method call: foo()
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "foo",
            ArgumentListExpression(),
        )

        val matcher = MethodCallMatcher("foo")
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.Match)
        assertTrue((result as DslMatchResult.Match).captures.isEmpty())
    }

    @Test
    fun `MethodCallMatcher should not match different method name`() {
        // Create a method call: bar()
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "bar",
            ArgumentListExpression(),
        )

        val matcher = MethodCallMatcher("foo")
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.NoMatch)
    }

    @Test
    fun `MethodCallMatcher should not match non-method-call expression`() {
        val stringLiteral = ConstantExpression("hello")

        val matcher = MethodCallMatcher("foo")
        val result = matcher.match(stringLiteral)

        assertTrue(result is DslMatchResult.NoMatch)
    }

    @Test
    fun `StringLiteralMatcher should capture string value`() {
        val stringLiteral = ConstantExpression("test-value")

        val matcher = StringLiteralMatcher("stringCapture")
        val result = matcher.match(stringLiteral)

        assertTrue(result is DslMatchResult.Match)
        val match = result as DslMatchResult.Match
        assertEquals("test-value", match.captures["stringCapture"])
    }

    @Test
    fun `StringLiteralMatcher should match string without capturing`() {
        val stringLiteral = ConstantExpression("test-value")

        val matcher = StringLiteralMatcher()
        val result = matcher.match(stringLiteral)

        assertTrue(result is DslMatchResult.Match)
        assertTrue((result as DslMatchResult.Match).captures.isEmpty())
    }

    @Test
    fun `StringLiteralMatcher should not match non-string constant`() {
        val intLiteral = ConstantExpression(42)

        val matcher = StringLiteralMatcher("numberCapture")
        val result = matcher.match(intLiteral)

        assertTrue(result is DslMatchResult.NoMatch)
    }

    @Test
    fun `ClosureMatcher should capture closure`() {
        val closure = ClosureExpression(
            emptyArray(),
            BlockStatement(),
        )

        val matcher = ClosureMatcher("closureCapture")
        val result = matcher.match(closure)

        assertTrue(result is DslMatchResult.Match)
        val match = result as DslMatchResult.Match
        assertSame(closure, match.captures["closureCapture"])
    }

    @Test
    fun `ClosureMatcher should match closure without capturing`() {
        val closure = ClosureExpression(
            emptyArray(),
            BlockStatement(),
        )

        val matcher = ClosureMatcher()
        val result = matcher.match(closure)

        assertTrue(result is DslMatchResult.Match)
        assertTrue((result as DslMatchResult.Match).captures.isEmpty())
    }

    @Test
    fun `ClosureMatcher should not match non-closure expression`() {
        val stringLiteral = ConstantExpression("not a closure")

        val matcher = ClosureMatcher("closureCapture")
        val result = matcher.match(stringLiteral)

        assertTrue(result is DslMatchResult.NoMatch)
    }

    @Test
    fun `AnyMatcher should match any expression`() {
        val expressions = listOf(
            ConstantExpression("string"),
            ConstantExpression(42),
            VariableExpression("x"),
            MethodCallExpression(VariableExpression("this"), "foo", ArgumentListExpression()),
        )

        val matcher = AnyMatcher()

        expressions.forEach { expr ->
            val result = matcher.match(expr)
            assertTrue(result is DslMatchResult.Match)
        }
    }

    @Test
    fun `AnyMatcher should capture expression when name provided`() {
        val expr = ConstantExpression("captured")

        val matcher = AnyMatcher("anyCapture")
        val result = matcher.match(expr)

        assertTrue(result is DslMatchResult.Match)
        val match = result as DslMatchResult.Match
        assertSame(expr, match.captures["anyCapture"])
    }

    @Test
    fun `MethodCallMatcher should match method with string argument`() {
        // Create: stage("Build")
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "stage",
            ArgumentListExpression(listOf(ConstantExpression("Build"))),
        )

        val matcher = MethodCallMatcher(
            "stage",
            argumentMatchers = listOf(StringLiteralMatcher("stageName")),
        )
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.Match)
        val match = result as DslMatchResult.Match
        assertEquals("Build", match.captures["stageName"])
    }

    @Test
    fun `MethodCallMatcher should match method with closure argument`() {
        // Create: pipeline { }
        val closure = ClosureExpression(
            emptyArray(),
            BlockStatement(),
        )
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "pipeline",
            ArgumentListExpression(listOf(closure)),
        )

        val matcher = MethodCallMatcher(
            "pipeline",
            argumentMatchers = listOf(ClosureMatcher("pipelineBlock")),
        )
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.Match)
        val match = result as DslMatchResult.Match
        assertSame(closure, match.captures["pipelineBlock"])
    }

    @Test
    fun `MethodCallMatcher should not match when argument count differs`() {
        // Create: foo("arg1", "arg2")
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "foo",
            ArgumentListExpression(
                listOf(
                    ConstantExpression("arg1"),
                    ConstantExpression("arg2"),
                ),
            ),
        )

        // Matcher expects only one argument
        val matcher = MethodCallMatcher(
            "foo",
            argumentMatchers = listOf(StringLiteralMatcher()),
        )
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.NoMatch)
    }

    @Test
    fun `nested matching - method with string and closure arguments`() {
        // Create: stage("Build") { steps { } }
        val stepsBlock = BlockStatement()
        val stageClosure = ClosureExpression(
            emptyArray(),
            BlockStatement(
                listOf(
                    ExpressionStatement(
                        MethodCallExpression(
                            VariableExpression("this"),
                            "steps",
                            ArgumentListExpression(
                                listOf(
                                    ClosureExpression(emptyArray(), stepsBlock),
                                ),
                            ),
                        ),
                    ),
                ),
                null,
            ),
        )
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "stage",
            ArgumentListExpression(
                listOf(
                    ConstantExpression("Build"),
                    stageClosure,
                ),
            ),
        )

        val matcher = MethodCallMatcher(
            "stage",
            argumentMatchers = listOf(
                StringLiteralMatcher("stageName"),
                ClosureMatcher("stageBody"),
            ),
        )
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.Match)
        val match = result as DslMatchResult.Match
        assertEquals("Build", match.captures["stageName"])
        assertSame(stageClosure, match.captures["stageBody"])
    }

    @Test
    fun `SequenceMatcher should match first matcher in sequence`() {
        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "foo",
            ArgumentListExpression(),
        )

        val matcher = SequenceMatcher(
            listOf(
                MethodCallMatcher("foo"),
                MethodCallMatcher("bar"),
            ),
        )
        val result = matcher.match(methodCall)

        assertTrue(result is DslMatchResult.Match)
    }

    @Test
    fun `SequenceMatcher with empty matchers should return Match`() {
        val expr = ConstantExpression("anything")

        val matcher = SequenceMatcher(emptyList())
        val result = matcher.match(expr)

        assertTrue(result is DslMatchResult.Match)
    }
}
