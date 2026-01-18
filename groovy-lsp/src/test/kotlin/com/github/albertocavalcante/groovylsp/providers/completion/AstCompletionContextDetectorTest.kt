package com.github.albertocavalcante.groovylsp.providers.completion

import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for AstCompletionContextDetector - AST-based completion context detection.
 *
 * These tests verify that the detector correctly identifies various completion contexts
 * by traversing the AST, replacing regex/text-based detection.
 *
 * TODO(#657): Most tests require real AST parsing to be implemented.
 * They are disabled until integration with the parser is complete.
 */
class AstCompletionContextDetectorTest {

    // =====================================================================
    // Context Type Tests (verify sealed class structure)
    // =====================================================================

    @Test
    fun `AstCompletionContext TopLevel is a valid context`() {
        val context: AstCompletionContext = AstCompletionContext.TopLevel
        assertIs<AstCompletionContext.TopLevel>(context)
    }

    @Test
    fun `AstCompletionContext Unknown is a valid context`() {
        val context: AstCompletionContext = AstCompletionContext.Unknown
        assertIs<AstCompletionContext.Unknown>(context)
    }

    @Test
    fun `AstCompletionContext ClosureBody tracks nesting depth`() {
        // Use a mock closure for testing the data class
        val context = AstCompletionContext.ClosureBody(
            closure = MockClosureExpression(),
            ownerType = null,
            delegateType = null,
            enclosingMethodCall = null,
            nestingDepth = 3,
        )

        assertEquals(3, context.nestingDepth)
        assertEquals(false, context.isDslBlock)
    }

    @Test
    fun `AstCompletionContext ClosureBody isDslBlock when enclosingMethodCall present`() {
        val context = AstCompletionContext.ClosureBody(
            closure = MockClosureExpression(),
            ownerType = null,
            delegateType = null,
            enclosingMethodCall = MockMethodCallExpression("dependencies"),
            nestingDepth = 1,
        )

        assertTrue(context.isDslBlock)
    }

    @Test
    fun `AstCompletionContext MemberAccess stores receiver info`() {
        val context = AstCompletionContext.MemberAccess(
            receiverType = null,
            receiverName = "myVar",
            isStatic = false,
        )

        assertEquals("myVar", context.receiverName)
        assertEquals(false, context.isStatic)
    }

    @Test
    fun `AstCompletionContext MethodArgument tracks index`() {
        val context = AstCompletionContext.MethodArgument(
            methodCall = MockMethodCallExpression("println"),
            argumentIndex = 0,
            expectedType = null,
        )

        assertEquals(0, context.argumentIndex)
    }

    @Test
    fun `AstCompletionContext ImportContext tracks prefix and static flag`() {
        val context = AstCompletionContext.ImportContext(
            prefix = "java.util.Li",
            isStatic = false,
        )

        assertEquals("java.util.Li", context.prefix)
        assertEquals(false, context.isStatic)
    }

    @Test
    fun `AstCompletionContext AnnotationContext stores annotation type`() {
        val context = AstCompletionContext.AnnotationContext(
            annotationType = null,
            attributeName = "value",
        )

        assertEquals("value", context.attributeName)
    }

    // =====================================================================
    // ClosureChain Tests
    // =====================================================================

    @Test
    fun `ClosureChain depth is zero for empty chain`() {
        val chain = ClosureChain(emptyList())
        assertEquals(0, chain.depth)
        assertEquals(null, chain.innermost)
        assertEquals(null, chain.outermost)
    }

    @Test
    fun `ClosureChain provides innermost and outermost`() {
        val outer = ClosureInfo(
            closure = MockClosureExpression(),
            ownerType = null,
            delegateType = null,
            delegationStrategy = 0,
            enclosingMethodCall = null,
        )
        val inner = ClosureInfo(
            closure = MockClosureExpression(),
            ownerType = null,
            delegateType = null,
            delegationStrategy = 0,
            enclosingMethodCall = null,
        )

        val chain = ClosureChain(listOf(outer, inner))

        assertEquals(2, chain.depth)
        assertEquals(inner, chain.innermost)
        assertEquals(outer, chain.outermost)
    }

    // =====================================================================
    // Detection Tests (require real AST - disabled until integration)
    // =====================================================================

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects closure context in simple closure`() {
        // Given: def foo = { println("hello") }
        // When: cursor inside the closure
        // Then: should detect ClosureBody context
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects nested closure context with correct depth`() {
        // Given: def foo = { { { println("nested") } } }
        // When: cursor in innermost closure
        // Then: should detect ClosureBody with nestingDepth = 3
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects Gradle dependencies DSL context`() {
        // Given: dependencies { implementation 'foo' }
        // When: cursor after 'implementation '
        // Then: should detect ClosureBody with isDslBlock = true
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects member access context after dot`() {
        // Given: myObject.
        // When: cursor after the dot
        // Then: should detect MemberAccess context with receiverName = "myObject"
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects method argument context`() {
        // Given: println(x, )
        // When: cursor at second argument position
        // Then: should detect MethodArgument with argumentIndex = 1
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects method body context`() {
        // Given: class Foo { void bar() { | } }
        // When: cursor inside method body
        // Then: should detect MethodBody context
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects class body context`() {
        // Given: class Foo { | }
        // When: cursor inside class body but outside any method
        // Then: should detect ClassBody context
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects annotation context`() {
        // Given: @Override()
        // When: cursor inside annotation parentheses
        // Then: should detect AnnotationContext
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `detects builder pattern from method chain`() {
        // Given: builder.name("foo").age(25).build()
        // When: analyzing the chain
        // Then: isBuilderPattern should return true
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `closure context preserves owner type`() {
        // Given: class Foo { def bar() { { this.baz() } } }
        // When: cursor inside closure
        // Then: ownerType should be Foo
    }

    @Test
    @Disabled("Requires real AST parsing - see TODO #657")
    fun `closure context infers delegate from DelegatesTo annotation`() {
        // Given: void run(@DelegatesTo(Builder) Closure c) { c() }
        // When: inside closure passed to run
        // Then: delegateType should be Builder
    }
}

// =====================================================================
// Mock Classes for Testing (without real AST)
// =====================================================================

/**
 * Mock ClosureExpression for unit testing without real AST.
 */
private class MockClosureExpression :
    ClosureExpression(
        arrayOf(),
        EmptyStatement.INSTANCE,
    )

/**
 * Mock MethodCallExpression for unit testing without real AST.
 */
private class MockMethodCallExpression(methodName: String) :
    MethodCallExpression(
        VariableExpression("this"),
        methodName,
        ArgumentListExpression(),
    )
