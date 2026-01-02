package com.github.albertocavalcante.groovyparser.ast.query

import org.codehaus.groovy.ast.expr.ConstantExpression
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AstNodeTypeTest {

    @Test
    fun `matches camel and snake case`() {
        val node = ConstantExpression("value")

        assertTrue(AstNodeType.matches(node, "ConstantExpression"))
        assertTrue(AstNodeType.matches(node, "constant_expression"))
        assertFalse(AstNodeType.matches(node, "constantexpression"))
    }
}
