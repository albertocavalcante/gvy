package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class GroovySemanticsTest {

    private val parser = GroovyParserFacade()

    private val stubSolver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> = SymbolReference.unsolved()
    }

    @Test
    fun `resolveType for string literal`() {
        val code = """
            def x = "hello"
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // "hello" is the right expression of the declaration
        val decl = findNode<DeclarationExpression>(module)!!
        val constantExpr = decl.rightExpression as ConstantExpression

        val type = semantics.resolveType(constantExpr)
        assertEquals(TypeConstants.STRING, type)
    }

    @Test
    fun `resolveType for local variable reference`() {
        val code = """
            def x = "hello"
            def y = x
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        val decls = findNodes<DeclarationExpression>(module)
        assertEquals(2, decls.size)

        val secondDecl = decls[1]
        val rhs = secondDecl.rightExpression
        assertTrue(rhs is VariableExpression)

        val type = semantics.resolveType(rhs)
        assertEquals(TypeConstants.STRING, type, "Variable 'x' should resolve to String inferred from initializer")
    }

    @Test
    fun `resolveType for declaration expression`() {
        val code = """
            String x = "hello"
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        val decl = findNode<DeclarationExpression>(module)!!
        val type = semantics.resolveType(decl)

        // String x ... explicit type is String
        assertEquals(TypeConstants.STRING, type)
    }

    private fun parse(code: String): ModuleNode {
        val request =
            ParseRequest(URI.create("file:///Test.groovy"), code)
        val result = parser.parse(request)
        if (!result.isSuccessful) {
            error("Parse failed: " + result.diagnostics)
        }
        return result.ast!!
    }

    private inline fun <reified T : ASTNode> findNode(module: ModuleNode): T? = findNodes<T>(module).firstOrNull()

    private inline fun <reified T : ASTNode> findNodes(module: ModuleNode): List<T> {
        val list = mutableListOf<T>()
        val block = module.statementBlock
        if (block != null) {
            visitBlock(block, list)
        }
        return list
    }

    private inline fun <reified T : ASTNode> visitBlock(block: BlockStatement, list: MutableList<T>) {
        for (stmt in block.statements) {
            if (stmt !is ExpressionStatement) continue
            val expr = stmt.expression
            if (expr is T) list.add(expr)

            if (expr is DeclarationExpression) {
                if (expr.leftExpression is T) list.add(expr.leftExpression as T)
                if (expr.rightExpression is T) list.add(expr.rightExpression as T)
            }
        }
    }
}
