package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.Phases
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

        @Suppress("DEPRECATION")
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

        @Suppress("DEPRECATION")
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

        @Suppress("DEPRECATION")
        val type = semantics.resolveType(decl)

        // String x ... explicit type is String
        assertEquals(TypeConstants.STRING, type)
    }

    @Test
    fun `resolveType with module parameter uses correct context`() {
        val code = """
            def x = "hello"
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)

        val decl = findNode<DeclarationExpression>(module)!!
        val constantExpr = decl.rightExpression as ConstantExpression

        // Use the new module-aware API (no need to call inject separately)
        val type = semantics.resolveType(constantExpr, module)
        assertEquals(TypeConstants.STRING, type)
    }

    @Test
    fun `resolveType with multiple modules resolves independently`() {
        val code1 = """
            def x = "string value"
        """.trimIndent()

        val code2 = """
            def y = 42
        """.trimIndent()

        val module1 = parse(code1)
        val module2 = parse(code2)
        val semantics = GroovySemantics(stubSolver)

        val decl1 = findNode<DeclarationExpression>(module1)!!
        val decl2 = findNode<DeclarationExpression>(module2)!!

        val expr1 = decl1.rightExpression as ConstantExpression
        val expr2 = decl2.rightExpression as ConstantExpression

        // Resolve with respective modules - should use correct context for each
        val type1 = semantics.resolveType(expr1, module1)
        val type2 = semantics.resolveType(expr2, module2)

        assertEquals(TypeConstants.STRING, type1, "First module should resolve to String")
        assertEquals(TypeConstants.INT, type2, "Second module should resolve to int")
    }

    private fun parse(code: String): ModuleNode {
        // Use CANONICALIZATION phase for proper type resolution (e.g., String -> java.lang.String)
        val request =
            ParseRequest(
                URI.create("file:///Test.groovy"),
                code,
                compilePhase = Phases.CANONICALIZATION,
            )
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

    // TDD: Method scope tracking tests

    @Test
    fun `resolveType for method parameter reference`() {
        val code = """
            class Foo {
                def process(String name) {
                    def x = name
                    return x
                }
            }
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // Find the variable expression 'name' on the RHS of 'def x = name'
        val classNode = module.classes.first()
        val method = classNode.methods.find { it.name == "process" }!!
        val methodBody = method.code as BlockStatement
        val firstStmt = methodBody.statements[0] as ExpressionStatement
        val decl = firstStmt.expression as DeclarationExpression
        val nameRef = decl.rightExpression as VariableExpression

        val type = semantics.resolveType(nameRef, module)
        assertEquals(TypeConstants.STRING, type, "Parameter 'name' should resolve to String")
    }

    @Test
    fun `resolveType for local variable in method body`() {
        val code = """
            class Foo {
                def calculate() {
                    def a = 10
                    def b = a
                    return b
                }
            }
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // Find 'a' reference in 'def b = a'
        val classNode = module.classes.first()
        val method = classNode.methods.find { it.name == "calculate" }!!
        val methodBody = method.code as BlockStatement
        val secondStmt = methodBody.statements[1] as ExpressionStatement
        val decl = secondStmt.expression as DeclarationExpression
        val aRef = decl.rightExpression as VariableExpression

        val type = semantics.resolveType(aRef, module)
        assertEquals(TypeConstants.INT, type, "Variable 'a' should resolve to int from initializer")
    }

    @Test
    fun `resolveType for binary expression with local variables`() {
        val code = """
            class Foo {
                def sum() {
                    def a = 1
                    def b = 2
                    def result = a + b
                    return result
                }
            }
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // Find the binary expression 'a + b'
        val classNode = module.classes.first()
        val method = classNode.methods.find { it.name == "sum" }!!
        val methodBody = method.code as BlockStatement
        val thirdStmt = methodBody.statements[2] as ExpressionStatement
        val decl = thirdStmt.expression as DeclarationExpression
        val binaryExpr = decl.rightExpression

        val type = semantics.resolveType(binaryExpr, module)
        assertEquals(TypeConstants.INT, type, "Binary expression 'a + b' should resolve to int")
    }

    @Test
    fun `resolveType for explicitly typed local variable`() {
        val code = """
            class Foo {
                def main() {
                    int a = 1
                    int b = 2
                    def sum = a + b
                    return sum
                }
            }
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // Find 'a + b' expression
        val classNode = module.classes.first()
        val method = classNode.methods.find { it.name == "main" }!!
        val methodBody = method.code as BlockStatement
        val thirdStmt = methodBody.statements[2] as ExpressionStatement
        val decl = thirdStmt.expression as DeclarationExpression
        val binaryExpr = decl.rightExpression

        val type = semantics.resolveType(binaryExpr, module)
        // Accept both int and Integer - LUB may return boxed type for mixed scenarios
        val isIntType = type == TypeConstants.INT ||
            (type is SemanticType.Known && type.fqn == "java.lang.Integer")
        assertTrue(
            isIntType,
            "Binary expression with explicitly typed int variables should resolve to int or Integer, got: $type",
        )
    }

    @Test
    fun `resolveType for map literal variable`() {
        val code = """
            def p = [key1: 'value1', key2: 'value2']
            def x = p
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // Find the variable reference 'p' on the RHS of 'def x = p'
        val decls = findNodes<DeclarationExpression>(module)
        assertEquals(2, decls.size, "Should have two declarations")

        val secondDecl = decls[1]
        val pRef = secondDecl.rightExpression as VariableExpression

        val type = semantics.resolveType(pRef, module)
        assertTrue(
            type is SemanticType.Known && type.fqn.contains("LinkedHashMap"),
            "Variable 'p' should resolve to LinkedHashMap from map literal, got: $type",
        )
    }

    @Test
    fun `resolveType for PropertyExpression object (completion scenario)`() {
        // This simulates what happens during completion when user types "p." after map declaration
        val code = """
            def p = [key1: 'value1', key2: 'value2']
            p.someProperty
        """.trimIndent()

        val module = parse(code)
        val semantics = GroovySemantics(stubSolver)
        semantics.inject(module)

        // Find the PropertyExpression 'p.someProperty'
        val block = module.statementBlock
        val secondStmt = block.statements[1] as ExpressionStatement
        val propExpr = secondStmt.expression as PropertyExpression
        val objectExpr = propExpr.objectExpression as VariableExpression

        // The objectExpression 'p' should resolve to LinkedHashMap
        val type = semantics.resolveType(objectExpr, module)
        assertTrue(
            type is SemanticType.Known && type.fqn.contains("LinkedHashMap"),
            "PropertyExpression.objectExpression 'p' should resolve to LinkedHashMap, got: $type",
        )
    }
}
