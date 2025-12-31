package com.github.albertocavalcante.groovyparser.resolution

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.resolution.contexts.CompilationUnitContext
import com.github.albertocavalcante.groovyparser.resolution.typeinference.TypeExtractor
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroovySymbolResolverTest {

    private val typeSolver = CombinedTypeSolver(ReflectionTypeSolver())
    private val parser = GroovyParser()

    @Test
    fun `resolves constant expression type via extractor`() {
        val cu = parser.parse("class Test { def x = 42 }").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val constant = ConstantExpr(42)
        val type = extractor.extractType(constant)

        assertEquals(ResolvedPrimitiveType.INT, type)
    }

    @Test
    fun `resolves field declaration type`() {
        val cu = parser.parse("class Test { String name }").result.get()
        val classDecl = cu.types.first() as ClassDeclaration
        val field = classDecl.fields.first()

        val resolver = GroovySymbolResolver(typeSolver)
        val type = resolver.resolveType(field)

        assertTrue(type.isReferenceType())
        assertEquals("java.lang.String", type.asReferenceType().declaration.qualifiedName)
    }

    @Test
    fun `resolves method return type`() {
        val cu = parser.parse("class Test { int getValue() { return 42 } }").result.get()
        val classDecl = cu.types.first() as ClassDeclaration
        val method = classDecl.methods.first()

        val resolver = GroovySymbolResolver(typeSolver)
        val type = resolver.resolveType(method)

        assertEquals(ResolvedPrimitiveType.INT, type)
    }

    @Test
    fun `solves type by name with imports`() {
        val cu = parser.parse(
            """
            import java.util.ArrayList
            class Test {}
            """.trimIndent(),
        ).result.get()

        val resolver = GroovySymbolResolver(typeSolver)
        val ref = resolver.solveType("ArrayList", cu)

        assertTrue(ref.isSolved)
        assertEquals("java.util.ArrayList", ref.getDeclaration().qualifiedName)
    }

    @Test
    fun `solves type from java lang without import`() {
        val cu = parser.parse("class Test {}").result.get()

        val resolver = GroovySymbolResolver(typeSolver)
        val ref = resolver.solveType("String", cu)

        assertTrue(ref.isSolved)
        assertEquals("java.lang.String", ref.getDeclaration().qualifiedName)
    }

    @Test
    fun `returns unsolved for unknown type`() {
        val cu = parser.parse("class Test {}").result.get()

        val resolver = GroovySymbolResolver(typeSolver)
        val ref = resolver.solveType("UnknownType", cu)

        assertFalse(ref.isSolved)
    }
}
