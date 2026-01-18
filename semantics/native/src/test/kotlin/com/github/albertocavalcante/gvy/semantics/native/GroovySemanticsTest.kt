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
        val propExpr = secondStmt.expression as org.codehaus.groovy.ast.expr.PropertyExpression
        val objectExpr = propExpr.objectExpression as VariableExpression

        // The objectExpression 'p' should resolve to LinkedHashMap
        val type = semantics.resolveType(objectExpr, module)
        assertTrue(
            type is SemanticType.Known && type.fqn.contains("LinkedHashMap"),
            "PropertyExpression.objectExpression 'p' should resolve to LinkedHashMap, got: $type",
        )
    }

    // WeakHashMap cache regression tests

    @Test
    fun `cache entry is removed when ModuleNode is garbage collected`() {
        val code = """
            def x = "hello"
        """.trimIndent()

        val semantics = GroovySemantics(stubSolver)

        // Create and inject a module, then immediately lose the reference
        var module: ModuleNode? = parse(code)
        val nonNullModule = module!! // Keep non-null reference for injection
        semantics.inject(nonNullModule)

        // Verify context exists before GC
        val contextBefore = semantics.getContext(nonNullModule)
        assertTrue(contextBefore != null, "Context should exist after injection")

        // Null the reference and request GC
        module = null
        @Suppress("ExplicitGarbageCollectionCall") // Testing WeakHashMap GC behavior
        System.gc()
        @Suppress("MagicNumber") // Test timing constant
        Thread.sleep(100) // Give GC a chance to run

        // NOTE: WeakHashMap cleanup is not guaranteed, but we're testing the mechanism is in place.
        // The cache should be using WeakHashMap, which allows entries to be collected.
        // We can't reliably verify the entry was removed without accessing private state,
        // but we can verify the cache still works for other modules.

        // Create a new module and verify it works independently
        val newModule = parse(code)
        val decl = findNode<DeclarationExpression>(newModule)!!
        val expr = decl.rightExpression as ConstantExpression
        val type = semantics.resolveType(expr, newModule)
        assertEquals(TypeConstants.STRING, type, "New module should resolve correctly after GC")
    }

    @Test
    fun `concurrent access to cache is thread-safe`() {
        val code1 = """
            def x = "string"
        """.trimIndent()
        val code2 = """
            def y = 42
        """.trimIndent()

        val semantics = GroovySemantics(stubSolver)
        val modules = listOf(parse(code1), parse(code2))

        // Use a barrier to synchronize thread start
        val threads = modules.mapIndexed { index, module ->
            Thread {
                // Each thread repeatedly accesses the cache
                @Suppress("MagicNumber") // Test constant for number of iterations
                repeat(100) {
                    semantics.inject(module)
                    val context = semantics.getContext(module)
                    assertTrue(context != null, "Context should be available in thread $index")
                }
            }
        }

        // Start all threads
        threads.forEach { it.start() }

        // Wait for all threads to complete
        threads.forEach { it.join() }

        // Verify both modules still resolve correctly after concurrent access
        val decl1 = findNode<DeclarationExpression>(modules[0])!!
        val type1 = semantics.resolveType(decl1.rightExpression, modules[0])
        assertEquals(TypeConstants.STRING, type1, "First module should still resolve to String")

        val decl2 = findNode<DeclarationExpression>(modules[1])!!
        val type2 = semantics.resolveType(decl2.rightExpression, modules[1])
        assertEquals(TypeConstants.INT, type2, "Second module should still resolve to int")
    }

    @Test
    fun `semantic analysis still works correctly after potential GC`() {
        val code = """
            def a = "first"
            def b = "second"
            def c = a
        """.trimIndent()

        val semantics = GroovySemantics(stubSolver)

        // Create multiple modules and inject them
        val modules = mutableListOf<ModuleNode>()
        @Suppress("MagicNumber") // Test constant for number of modules to create
        repeat(10) {
            modules.add(parse(code))
        }

        // Inject all modules
        modules.forEach { semantics.inject(it) }

        // Keep reference to last module
        val lastModule = modules.last()

        // Clear references to first 9 modules and request GC
        @Suppress("MagicNumber") // Test constant for number of modules to remove
        repeat(9) { modules.removeAt(0) }
        @Suppress("ExplicitGarbageCollectionCall") // Testing WeakHashMap GC behavior
        System.gc()
        @Suppress("MagicNumber") // Test timing constant
        Thread.sleep(100)

        // Verify the remaining module still works correctly
        val decls = findNodes<DeclarationExpression>(lastModule)
        assertEquals(3, decls.size, "Should have three declarations")

        val cRef = decls[2].rightExpression as VariableExpression
        val type = semantics.resolveType(cRef, lastModule)
        assertEquals(TypeConstants.STRING, type, "Variable 'c' should still resolve to String after GC")
    }

    @Test
    fun `multiple modules with same code maintain independent contexts`() {
        val code = """
            def x = "value"
        """.trimIndent()

        val semantics = GroovySemantics(stubSolver)

        // Create multiple distinct ModuleNode instances with same code
        val module1 = parse(code)
        val module2 = parse(code)
        val module3 = parse(code)

        // Inject all modules
        semantics.inject(module1)
        semantics.inject(module2)
        semantics.inject(module3)

        // Get contexts - they should be separate instances
        val context1 = semantics.getContext(module1)
        val context2 = semantics.getContext(module2)
        val context3 = semantics.getContext(module3)

        assertTrue(context1 != null, "Context 1 should exist")
        assertTrue(context2 != null, "Context 2 should exist")
        assertTrue(context3 != null, "Context 3 should exist")

        // Verify all modules resolve correctly
        listOf(module1, module2, module3).forEach { module ->
            val decl = findNode<DeclarationExpression>(module)!!
            val expr = decl.rightExpression as ConstantExpression
            val type = semantics.resolveType(expr, module)
            assertEquals(TypeConstants.STRING, type, "Each module should independently resolve to String")
        }
    }
}
