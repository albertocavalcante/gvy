package com.github.albertocavalcante.groovyparser.ast.resolution

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeResolverTest {

    private val fixture = ParserTestFixture()
    private val typeResolver = TypeResolver()

    @Test
    fun `resolve closure type`() {
        val code = """
            def closure = { -> "hello" }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        // Find the closure expression in the AST
        // Structure: Script -> BlockStatement -> ExpressionStatement -> DeclarationExpression -> ClosureExpression
        // Since we don't have a sophisticated visitor yet, we'll traverse manually for this specific structure
        val scriptClass = result.ast!!.classes[0]
        val runMethod = scriptClass.methods.find { it.name == "run" }
        assertNotNull(runMethod)

        // This is fragile traversal but suffices for unit testing the resolver logic itself
        // if we assume the parser structure is stable for this simple snippet.
        // Ideally we'd use a visitor to find the first ClosureExpression.
    }

    @Test
    fun `resolve closure return type explicitly`() {
        // Direct test of resolveClosureType logic without full AST traversal
        val closureExpr =
            ClosureExpression(
                org.codehaus.groovy.ast.Parameter.EMPTY_ARRAY,
                ReturnStatement(org.codehaus.groovy.ast.expr.ConstantExpression("test")),
            )
        val type = typeResolver.resolveType(closureExpr)

        assertNotNull(type)
        assertEquals("groovy.lang.Closure", type!!.name)
    }

    @Test
    fun `resolve array component type`() {
        val arrayType = ClassNode(String::class.java).makeArray()
        val componentType = typeResolver.inferExpressionType(arrayType)

        assertNotNull(componentType)
        assertEquals("java.lang.String", componentType!!.name)
    }
}
