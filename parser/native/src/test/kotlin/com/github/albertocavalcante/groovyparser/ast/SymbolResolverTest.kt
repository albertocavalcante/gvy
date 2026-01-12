package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SymbolResolverTest {

    private val parser = ParserTestFixture()

    @Test
    fun `resolveSymbol selects nearest parameter when names are duplicated across methods`() {
        val code = """
            class Sample {
                def method1(param) {
                    println param
                }
                def method2(param) {
                    println param
                }
            }
        """.trimIndent()

        val result = parser.parse(code)
        val astModel = result.astModel
        val symbolTable = result.symbolTable

        val paramReferences = astModel.getAllNodes()
            .filterIsInstance<VariableExpression>()
            .filter { it.name == "param" }
            .sortedBy { it.lineNumber }

        assertEquals(2, paramReferences.size, "Expected two param references")

        val firstMethod = resolveMethodName(symbolTable, astModel, paramReferences[0])
        val secondMethod = resolveMethodName(symbolTable, astModel, paramReferences[1])

        assertEquals("method1", firstMethod)
        assertEquals("method2", secondMethod)
    }

    private fun resolveMethodName(
        symbolTable: SymbolTable,
        astModel: GroovyAstModel,
        reference: VariableExpression,
    ): String {
        val resolved = symbolTable.resolveSymbol(reference, astModel)
        assertNotNull(resolved, "Expected resolved symbol for ${reference.name}")

        val resolvedNode = resolved as? ASTNode
        assertNotNull(resolvedNode, "Resolved symbol should be an AST node")
        var current: ASTNode? = resolvedNode
        var method: MethodNode? = null
        while (current != null) {
            if (current is MethodNode) {
                method = current
                break
            }
            current = astModel.getParent(current)
        }

        assertNotNull(method, "Expected symbol to resolve within a method")
        return method.name
    }
}
