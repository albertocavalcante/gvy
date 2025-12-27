package com.github.albertocavalcante.groovyparser.ast.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AstQueryParserTest {

    @Test
    fun `parses simple pattern`() {
        val query = AstQuery.parse("(ConstantExpression)")

        assertEquals(1, query.patterns.size)
        val pattern = query.patterns.single()
        assertEquals("ConstantExpression", pattern.type)
        assertNull(pattern.capture)
        assertTrue(pattern.children.isEmpty())
    }

    @Test
    fun `parses capture`() {
        val query = AstQuery.parse("(ConstantExpression @value)")

        val pattern = query.patterns.single()
        assertEquals("ConstantExpression", pattern.type)
        assertEquals("value", pattern.capture)
    }

    @Test
    fun `parses nested patterns`() {
        val query = AstQuery.parse("(BlockStatement (ConstantExpression))")

        val pattern = query.patterns.single()
        assertEquals("BlockStatement", pattern.type)
        assertEquals(1, pattern.children.size)
        assertEquals("ConstantExpression", pattern.children.single().type)
    }

    @Test
    fun `parses multiple top level patterns`() {
        val query = AstQuery.parse("(BlockStatement) (ConstantExpression)")

        assertEquals(2, query.patterns.size)
    }
}
