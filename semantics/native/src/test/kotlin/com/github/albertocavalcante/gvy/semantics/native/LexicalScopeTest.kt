package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class LexicalScopeTest {
    private val solver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> = SymbolReference.unsolved()
    }

    @Test
    fun `same name parameters and locals preserve declaration identity across method order`() {
        val first = """Integer a(Integer x) { def probe = x }; String b(String x) { def probe = x }"""
        val second = """String b(String x) { def probe = x }; Integer a(Integer x) { def probe = x }"""
        listOf(first, second).forEach { methods ->
            val module = parse("class Sample { $methods }")
            val semantics = GroovySemantics(solver)
            val cls = module.classes.first { it.name == "Sample" }
            val a = cls.methods.first { it.name == "a" }.code as BlockStatement
            val b = cls.methods.first { it.name == "b" }.code as BlockStatement
            fun rhs(body: BlockStatement) = ((body.statements.first() as ExpressionStatement).expression as DeclarationExpression).rightExpression
            assertEquals(SemanticType.Known("java.lang.Integer"), semantics.resolveType(rhs(a), module))
            assertEquals(TypeConstants.STRING, semantics.resolveType(rhs(b), module))
        }
    }

    @Test
    fun `same name inferred locals remain distinct across methods`() {
        val module = parse("class Sample { def a() { def x = 42; def probe = x }; def b() { def x = 's'; def probe = x } }")
        val semantics = GroovySemantics(solver)
        val cls = module.classes.first { it.name == "Sample" }
        fun probe(method: String) = ((cls.methods.first { it.name == method }.code as BlockStatement)
            .statements[1] as ExpressionStatement).expression as DeclarationExpression
        assertEquals(TypeConstants.INT, semantics.resolveType(probe("a").rightExpression, module))
        assertEquals(TypeConstants.STRING, semantics.resolveType(probe("b").rightExpression, module))
    }

    @Test
    fun `nested local bindings remain isolated across modules`() {
        val semantics = GroovySemantics(solver)
        val left = parse("class A { def m() { if (true) { def x = 1; def probe = x } } }")
        val right = parse("class B { def m() { if (true) { def x = 's'; def probe = x } } }")
        fun probe(module: org.codehaus.groovy.ast.ModuleNode): DeclarationExpression {
            val code = module.classes.first().methods.first { it.name == "m" }.code as BlockStatement
            val branch = (code.statements.first() as org.codehaus.groovy.ast.stmt.IfStatement).ifBlock as BlockStatement
            return (branch.statements[1] as ExpressionStatement).expression as DeclarationExpression
        }
        assertEquals(TypeConstants.INT, semantics.resolveType(probe(left).rightExpression, left))
        assertEquals(TypeConstants.STRING, semantics.resolveType(probe(right).rightExpression, right))
        assertEquals(TypeConstants.INT, semantics.resolveType(probe(left).rightExpression, left))
    }

    @Test
    fun `sibling closure local declarations retain their own inferred types`() {
        val module = parse("""
            class Sample {
                def m() {
                    def one = { def x = 1; def probe = x }
                    def two = { def x = 's'; def probe = x }
                }
            }
        """.trimIndent())
        val method = module.classes.first { it.name == "Sample" }.methods.first { it.name == "m" }
        val statements = (method.code as BlockStatement).statements
        fun probe(index: Int): DeclarationExpression {
            val outer = (statements[index] as ExpressionStatement).expression as DeclarationExpression
            val body = (outer.rightExpression as ClosureExpression).code as BlockStatement
            return (body.statements[1] as ExpressionStatement).expression as DeclarationExpression
        }
        val semantics = GroovySemantics(solver)
        assertEquals(TypeConstants.INT, semantics.resolveType(probe(0).rightExpression, module))
        assertEquals(TypeConstants.STRING, semantics.resolveType(probe(1).rightExpression, module))
    }

    @Test
    fun `symbol extraction uses the matching method parameter`() {
        val module = parse("class Sample { def a(Integer x) { def result = x }; def b(String x) { def result = x } }")
        val semantics = GroovySemantics(solver)
        val cls = module.classes.first { it.name == "Sample" }
        val method = cls.methods.first { it.name == "a" }
        val result = SymbolExtractor.extractVariableSymbols(method, semantics, module).first { it.name == "result" }
        assertEquals("java.lang.Integer", result.type)
    }

    @Test
    fun `field and closure parameter remain distinct from unrelated parameters`() {
        val module = parse("""
            class Sample {
                String x
                def fieldProbe() { def probe = x }
                def closureProbe() { def c = { Integer x -> def probe = x } }
                def other(Boolean x) { def probe = x }
            }
        """.trimIndent())
        val semantics = GroovySemantics(solver)
        val cls = module.classes.first { it.name == "Sample" }
        fun firstDeclaration(method: String): DeclarationExpression =
            ((cls.methods.first { it.name == method }.code as BlockStatement)
                .statements.first() as ExpressionStatement).expression as DeclarationExpression
        val closure = firstDeclaration("closureProbe").rightExpression as ClosureExpression
        val closureProbe = ((closure.code as BlockStatement).statements.first() as ExpressionStatement)
            .expression as DeclarationExpression
        assertEquals(TypeConstants.STRING, semantics.resolveType(firstDeclaration("fieldProbe").rightExpression, module))
        assertEquals(SemanticType.Known("java.lang.Integer"), semantics.resolveType(closureProbe.rightExpression, module))
    }

    private fun parse(code: String) = checkNotNull(
        GroovyParserFacade().parse(ParseRequest(URI.create("file:///Sample.groovy"), code, compilePhase = Phases.CANONICALIZATION)).ast,
    )
}
