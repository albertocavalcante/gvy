package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class TypeInferencerTest {

    private val parser = GroovyParserFacade()

    @Test
    fun `should infer ArrayList for empty list literal`() {
        val code = "def list = []"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.ArrayList", type)
    }

    @Test
    fun `should infer ArrayList for non-empty list literal`() {
        val code = "def list = [1, 2, 3]"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.ArrayList<java.lang.Integer>", type)
    }

    @Test
    fun `should infer LinkedHashMap for empty map literal`() {
        val code = "def map = [:]"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.LinkedHashMap", type)
    }

    @Test
    fun `should infer LinkedHashMap for non-empty map literal`() {
        val code = "def map = [a: 1, b: 2]"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.LinkedHashMap", type)
    }

    @Test
    fun `should use explicit type if provided`() {
        val code = "java.util.List list = []"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.List", type)
    }

    @Test
    fun `should use explicit type even if initializer is different (Groovy allows this)`() {
        val code = "java.util.List list = [:]" // Semantically weird but syntactically valid assignment
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.List", type)
    }

    @Test
    fun `should default to Object for unknown types`() {
        val code = "def x = 'hello'" // ConstantExpression type is String
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.lang.String", type)
    }

    @Test
    fun `should infer ArrayList with String generic for user example`() {
        val code = "def hello = ['a', 'b']"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.ArrayList<java.lang.String>", type)
    }

    @Test
    fun `should infer ArrayList with Object generic for mixed types`() {
        val code = "def hello = ['a', 1]"
        val type = inferTypeFromFirstDeclaration(code)
        assertEquals("java.util.ArrayList<java.lang.Object>", type)
    }

    private fun inferTypeFromFirstDeclaration(code: String): String {
        val uri = URI.create("file:///test.groovy")
        val request = ParseRequest(uri = uri, content = code)
        val parseResult = parser.parse(request)
        val ast = parseResult.ast ?: throw IllegalStateException("Failed to parse AST")

        // Find the first declaration
        val statement = ast.statementBlock.statements.firstOrNull()
            ?: throw IllegalStateException("No statements found")

        if (statement is ExpressionStatement && statement.expression is DeclarationExpression) {
            return TypeInferencer.inferType(statement.expression as DeclarationExpression)
        }

        throw IllegalStateException("First statement is not a declaration: $statement")
    }
}
