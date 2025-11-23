package com.github.albertocavalcante.groovyparser.ast.resolution

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DefinitionResolverTest {

    private val fixture = ParserTestFixture()
    private val resolver = DefinitionResolver()

    @Test
    fun `resolve definition of class`() {
        val code = """
            class MyClass {}
        """.trimIndent()

        val result = fixture.parse(code)
        val classNode = result.ast?.classes?.first()

        assertNotNull(classNode)
        val def = resolver.resolveDefinition(classNode!!)

        // Should resolve to itself
        assertEquals(classNode, def)
    }

    @Test
    fun `resolve definition of variable returns null for now`() {
        // Minimal stub test
        val variable = org.codehaus.groovy.ast.expr.VariableExpression("test")
        val def = resolver.resolveDefinition(variable)

        // Current implementation is stubbed
        assertEquals(null, def)
    }
}
