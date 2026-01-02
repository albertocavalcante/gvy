package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.resolution.contexts.CompilationUnitContext
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeExtractorTest {

    private val typeSolver = CombinedTypeSolver(ReflectionTypeSolver())
    private val parser = GroovyParser()

    @Test
    fun `infers int constant type`() {
        val cu = parser.parse("class Test { def x = 42 }").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val constant = ConstantExpr(42)
        val type = extractor.extractType(constant)

        assertTrue(type.isPrimitive())
        assertEquals(ResolvedPrimitiveType.INT, type)
    }

    @Test
    fun `infers long constant type`() {
        val cu = parser.parse("class Test { def x = 42L }").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val constant = ConstantExpr(42L)
        val type = extractor.extractType(constant)

        assertTrue(type.isPrimitive())
        assertEquals(ResolvedPrimitiveType.LONG, type)
    }

    @Test
    fun `infers double constant type`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val constant = ConstantExpr(3.14)
        val type = extractor.extractType(constant)

        assertTrue(type.isPrimitive())
        assertEquals(ResolvedPrimitiveType.DOUBLE, type)
    }

    @Test
    fun `infers boolean constant type`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val constant = ConstantExpr(true)
        val type = extractor.extractType(constant)

        assertTrue(type.isPrimitive())
        assertEquals(ResolvedPrimitiveType.BOOLEAN, type)
    }

    @Test
    fun `infers String constant type`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val constant = ConstantExpr("hello")
        val type = extractor.extractType(constant)

        assertTrue(type.isReferenceType())
        assertEquals("java.lang.String", type.asReferenceType().declaration.qualifiedName)
    }

    @Test
    fun `infers comparison operator returns boolean`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val binary = BinaryExpr(ConstantExpr(1), "<", ConstantExpr(2))
        val type = extractor.extractType(binary)

        assertEquals(ResolvedPrimitiveType.BOOLEAN, type)
    }

    @Test
    fun `infers arithmetic promotes to wider type`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val binary = BinaryExpr(ConstantExpr(1), "+", ConstantExpr(2.0))
        val type = extractor.extractType(binary)

        assertEquals(ResolvedPrimitiveType.DOUBLE, type)
    }

    @Test
    fun `infers string concatenation returns String`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val binary = BinaryExpr(ConstantExpr("hello"), "+", ConstantExpr(42))
        val type = extractor.extractType(binary)

        assertTrue(type.isReferenceType())
        assertEquals("java.lang.String", type.asReferenceType().declaration.qualifiedName)
    }

    @Test
    fun `infers list literal type`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val list = ListExpr(listOf(ConstantExpr(1), ConstantExpr(2), ConstantExpr(3)))
        val type = extractor.extractType(list)

        assertTrue(type.isReferenceType())
        assertTrue(type.asReferenceType().declaration.qualifiedName.contains("ArrayList"))
    }

    @Test
    fun `infers map literal type`() {
        val cu = parser.parse("class Test {}").result.get()
        val context = CompilationUnitContext(cu, typeSolver)
        val extractor = TypeExtractor(typeSolver, context)

        val map = MapExpr()
        val type = extractor.extractType(map)

        assertTrue(type.isReferenceType())
        assertTrue(type.asReferenceType().declaration.qualifiedName.contains("Map"))
    }
}
