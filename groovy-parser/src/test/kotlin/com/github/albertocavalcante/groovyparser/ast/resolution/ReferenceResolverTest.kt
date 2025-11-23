package com.github.albertocavalcante.groovyparser.ast.resolution

import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceResolverTest {

    private val resolver = ReferenceResolver()

    @Test
    fun `find references to variable returns empty list for now`() {
        // Current implementation is a stub, so we verify it doesn't crash
        // and returns an empty list as defined.
        val variable = VariableExpression("test")
        val scope = org.codehaus.groovy.ast.stmt.BlockStatement()

        val refs = resolver.findReferences(variable, scope)
        assertTrue(refs.isEmpty())
    }

    @Test
    fun `find references to method returns empty list for now`() {
        val methodCall =
            MethodCallExpression(
                VariableExpression("this"),
                "test",
                org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS,
            )
        val scope = org.codehaus.groovy.ast.stmt.BlockStatement()

        val refs = resolver.findReferences(methodCall, scope)
        assertTrue(refs.isEmpty())
    }
}
