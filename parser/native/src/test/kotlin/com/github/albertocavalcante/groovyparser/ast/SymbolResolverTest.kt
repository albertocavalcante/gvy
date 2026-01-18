package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun `resolveSymbol respects block-scoped declarations`() {
        val code = """
            class Sample {
                def method() {
                    def x = 1
                    if (true) {
                        def x = 2
                        println x
                    }
                    println x
                }
            }
        """.trimIndent()

        val result = parser.parse(code)
        val astModel = result.astModel
        val symbolTable = result.symbolTable

        val innerReference = findVariableReference(astModel, "x", 6)
        val outerReference = findVariableReference(astModel, "x", 8)

        assertEquals(5, resolveDefinitionLine(symbolTable, astModel, innerReference))
        assertEquals(3, resolveDefinitionLine(symbolTable, astModel, outerReference))
    }

    @Test
    fun `resolveSymbol prefers closure parameters over method parameters`() {
        val code = """
            class Sample {
                def method(x) {
                    def closure = { x ->
                        println x
                    }
                    println x
                }
            }
        """.trimIndent()

        val result = parser.parse(code)
        val astModel = result.astModel
        val symbolTable = result.symbolTable

        val innerReference = findVariableReference(astModel, "x", 4)
        val outerReference = findVariableReference(astModel, "x", 6)

        val innerResolved = resolveDefinitionNode(symbolTable, astModel, innerReference)
        val outerResolved = resolveDefinitionNode(symbolTable, astModel, outerReference)

        val innerScope = findEnclosingScope(astModel, innerResolved)
        val outerScope = findEnclosingScope(astModel, outerResolved)

        assertTrue(innerScope is ClosureExpression, "Expected closure parameter resolution inside closure")
        assertTrue(outerScope is MethodNode, "Expected method parameter resolution outside closure")
    }

    @Test
    fun `resolveSymbol keeps declaration when shadowing a parameter`() {
        val code = """
            class Sample {
                def method(x) {
                    def x = 1
                    println x
                }
            }
        """.trimIndent()

        val result = parser.parse(code)
        val astModel = result.astModel
        val symbolTable = result.symbolTable

        val declarationReference = findVariableReference(astModel, "x", 3)
        val usageReference = findVariableReference(astModel, "x", 4)

        assertEquals(3, resolveDefinitionLine(symbolTable, astModel, declarationReference))
        assertEquals(3, resolveDefinitionLine(symbolTable, astModel, usageReference))
    }

    private fun resolveMethodName(
        symbolTable: SymbolTable,
        astModel: GroovyAstModel,
        reference: VariableExpression,
    ): String {
        val resolvedNode = resolveDefinitionNode(symbolTable, astModel, reference)
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

    private fun resolveDefinitionLine(
        symbolTable: SymbolTable,
        astModel: GroovyAstModel,
        reference: VariableExpression,
    ): Int = resolveDefinitionNode(symbolTable, astModel, reference).lineNumber

    private fun resolveDefinitionNode(
        symbolTable: SymbolTable,
        astModel: GroovyAstModel,
        reference: VariableExpression,
    ): ASTNode {
        val resolved = symbolTable.resolveSymbol(reference, astModel)
        assertNotNull(resolved, "Expected resolved symbol for ${reference.name}")

        val resolvedNode = resolved as? ASTNode
        assertNotNull(resolvedNode, "Resolved symbol should be an AST node")
        return resolvedNode
    }

    private fun findVariableReference(astModel: GroovyAstModel, name: String, lineNumber: Int): VariableExpression =
        astModel.getAllNodes()
            .filterIsInstance<VariableExpression>()
            .first { it.name == name && it.lineNumber == lineNumber }

    private fun findEnclosingScope(astModel: GroovyAstModel, node: ASTNode): ASTNode? {
        var current = astModel.getParent(node)
        while (current != null) {
            if (current is MethodNode ||
                current is ConstructorNode ||
                current is ClosureExpression ||
                current is BlockStatement ||
                current is ClassNode
            ) {
                return current
            }
            current = astModel.getParent(current)
        }
        return null
    }
}
