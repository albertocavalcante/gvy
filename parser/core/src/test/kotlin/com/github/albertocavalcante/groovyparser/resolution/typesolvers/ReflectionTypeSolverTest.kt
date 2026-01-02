package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReflectionTypeSolverTest {

    @Test
    fun `resolves java lang String`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.lang.String")

        assertTrue(ref.isSolved)
        assertEquals("java.lang.String", ref.getDeclaration().qualifiedName)
        assertTrue(ref.getDeclaration().isClass())
    }

    @Test
    fun `resolves java util List interface`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.util.List")

        assertTrue(ref.isSolved)
        assertEquals("java.util.List", ref.getDeclaration().qualifiedName)
        assertTrue(ref.getDeclaration().isInterface())
    }

    @Test
    fun `resolves java util concurrent TimeUnit enum`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.util.concurrent.TimeUnit")

        assertTrue(ref.isSolved)
        assertEquals("java.util.concurrent.TimeUnit", ref.getDeclaration().qualifiedName)
        assertTrue(ref.getDeclaration().isEnum())
    }

    @Test
    fun `returns unsolved for non-existent type`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("com.nonexistent.Type")

        assertFalse(ref.isSolved)
    }

    @Test
    fun `jreOnly mode blocks non-jre types`() {
        val solver = ReflectionTypeSolver(jreOnly = true)
        val ref = solver.tryToSolveType("org.junit.jupiter.api.Test")

        assertFalse(ref.isSolved)
    }

    @Test
    fun `non-jreOnly mode allows all types`() {
        val solver = ReflectionTypeSolver(jreOnly = false)
        val ref = solver.tryToSolveType("org.junit.jupiter.api.Test")

        assertTrue(ref.isSolved)
    }

    @Test
    fun `resolves class with superclass`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.util.ArrayList")

        assertTrue(ref.isSolved)
        val classDecl = ref.getDeclaration().asClass()
        assertNotNull(classDecl.superClass)
    }

    @Test
    fun `resolves class with interfaces`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.util.ArrayList")

        assertTrue(ref.isSolved)
        val classDecl = ref.getDeclaration().asClass()
        assertTrue(classDecl.interfaces.isNotEmpty())
    }

    @Test
    fun `resolves declared methods`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.lang.String")

        assertTrue(ref.isSolved)
        val methods = ref.getDeclaration().getDeclaredMethods()
        assertTrue(methods.any { it.name == "length" })
        assertTrue(methods.any { it.name == "charAt" })
    }

    @Test
    fun `resolves declared fields`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.lang.Integer")

        assertTrue(ref.isSolved)
        val fields = ref.getDeclaration().getDeclaredFields()
        assertTrue(fields.any { it.name == "MAX_VALUE" })
        assertTrue(fields.any { it.name == "MIN_VALUE" })
    }

    @Test
    fun `resolves method return type`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.lang.String")

        assertTrue(ref.isSolved)
        val lengthMethod = ref.getDeclaration().getDeclaredMethods().first { it.name == "length" }
        assertTrue(lengthMethod.returnType.isPrimitive())
        assertEquals("int", lengthMethod.returnType.describe())
    }

    @Test
    fun `resolves method parameters`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.lang.String")

        assertTrue(ref.isSolved)
        val charAtMethod = ref.getDeclaration().getDeclaredMethods().first { it.name == "charAt" }
        assertEquals(1, charAtMethod.getNumberOfParams())
        assertTrue(charAtMethod.getParam(0).type.isPrimitive())
    }

    @Test
    fun `resolves enum constants`() {
        val solver = ReflectionTypeSolver()
        val ref = solver.tryToSolveType("java.util.concurrent.TimeUnit")

        assertTrue(ref.isSolved)
        val enumDecl = ref.getDeclaration().asEnum()
        val constants = enumDecl.getEnumConstants()
        assertTrue(constants.any { it.name == "SECONDS" })
        assertTrue(constants.any { it.name == "MILLISECONDS" })
    }

    @Test
    fun `caches resolved types`() {
        val solver = ReflectionTypeSolver()

        val ref1 = solver.tryToSolveType("java.lang.String")
        val ref2 = solver.tryToSolveType("java.lang.String")

        assertTrue(ref1.isSolved)
        assertTrue(ref2.isSolved)
        // Both should return the same declaration instance due to caching
        assertEquals(ref1.getDeclaration(), ref2.getDeclaration())
    }

    @Test
    fun `solves boxed type for primitives`() {
        val solver = ReflectionTypeSolver()

        val ref = solver.solveBoxedType("int")
        assertTrue(ref.isSolved)
        assertEquals("java.lang.Integer", ref.getDeclaration().qualifiedName)
    }

    @Test
    fun `hasType returns true for existing type`() {
        val solver = ReflectionTypeSolver()
        assertTrue(solver.hasType("java.lang.String"))
    }

    @Test
    fun `hasType returns false for non-existing type`() {
        val solver = ReflectionTypeSolver()
        assertFalse(solver.hasType("com.nonexistent.Type"))
    }
}
