package com.github.albertocavalcante.groovyparser.resolution

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that resolved declarations expose their source AST nodes
 * for navigation purposes (go-to-definition).
 */
class DeclarationNodeExposureTest {

    private val typeSolver = CombinedTypeSolver(ReflectionTypeSolver())
    private val parser = GroovyParser()

    @Test
    fun `parameter declaration exposes its AST node`() {
        val cu = parser.parse(
            """
            class Test {
                void greet(String name) {
                    println name
                }
            }
            """.trimIndent(),
        ).result.get()

        val classDecl = cu.types.first() as ClassDeclaration
        val method = classDecl.methods.first()
        val block = method.body as? BlockStatement ?: error("No block statement found")
        val stmt = block.statements.first()

        // Find the variable expression (usage of 'name' parameter)
        val nameExpr = findVariableExpr(stmt, "name")
            ?: error("No VariableExpr 'name' found in println statement")

        val resolver = GroovySymbolResolver(typeSolver)
        val symbolRef = resolver.solveSymbol("name", nameExpr)

        assertTrue(symbolRef.isSolved, "Symbol 'name' should be resolved")

        val declaration = symbolRef.getDeclaration()
        assertNotNull(declaration.declarationNode, "Declaration should expose its AST node")

        // The node should be the Parameter node
        val declNode = declaration.declarationNode
        assertTrue(declNode is Parameter, "Declaration node should be a Parameter")
        assertEquals("name", declNode.name)
    }

    @Test
    fun `field declaration exposes its AST node`() {
        val cu = parser.parse(
            """
            class Test {
                String message = 'hello'
                
                void print() {
                    println message
                }
            }
            """.trimIndent(),
        ).result.get()

        val classDecl = cu.types.first() as ClassDeclaration
        val method = classDecl.methods.first()
        val block = method.body as? BlockStatement ?: error("No block statement found")
        val stmt = block.statements.first()

        // Find the variable expression (usage of 'message' field)
        val messageExpr = findVariableExpr(stmt, "message")
            ?: error("No VariableExpr 'message' found in println statement")

        val resolver = GroovySymbolResolver(typeSolver)
        val symbolRef = resolver.solveSymbol("message", messageExpr)

        assertTrue(symbolRef.isSolved, "Symbol 'message' should be resolved")

        val declaration = symbolRef.getDeclaration()
        assertNotNull(declaration.declarationNode, "Declaration should expose its AST node")

        // Verify the node has the correct source position
        val declNode = declaration.declarationNode
        assertTrue(declNode is FieldDeclaration, "Declaration node should be a FieldDeclaration")
        assertNotNull(declNode.range, "Declaration node should have a range")
    }

    /**
     * Recursively finds a VariableExpr with the given name in the AST.
     */
    private fun findVariableExpr(node: Node, name: String): VariableExpr? {
        if (node is VariableExpr && node.name == name) {
            return node
        }
        for (child in node.getChildNodes()) {
            val found = findVariableExpr(child, name)
            if (found != null) return found
        }
        return null
    }
}
