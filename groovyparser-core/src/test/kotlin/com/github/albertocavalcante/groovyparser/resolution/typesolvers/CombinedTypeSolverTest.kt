package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombinedTypeSolverTest {

    @Test
    fun `resolves type from first solver`() {
        val solver = CombinedTypeSolver(ReflectionTypeSolver())

        val ref = solver.tryToSolveType("java.lang.String")
        assertTrue(ref.isSolved)
        assertEquals("java.lang.String", ref.getDeclaration().qualifiedName)
    }

    @Test
    fun `returns unsolved when no solver can resolve`() {
        val solver = CombinedTypeSolver(ReflectionTypeSolver(jreOnly = true))

        val ref = solver.tryToSolveType("com.nonexistent.Type")
        assertFalse(ref.isSolved)
    }

    @Test
    fun `tries multiple solvers in order`() {
        val jreSolver = ReflectionTypeSolver(jreOnly = true)
        val allSolver = ReflectionTypeSolver(jreOnly = false)

        val combined = CombinedTypeSolver(jreSolver, allSolver)

        // JUnit is not in JRE, but should be found by the second solver
        val ref = combined.tryToSolveType("org.junit.jupiter.api.Test")
        assertTrue(ref.isSolved)
    }

    @Test
    fun `caches resolved types`() {
        val solver = CombinedTypeSolver(ReflectionTypeSolver())

        val ref1 = solver.tryToSolveType("java.lang.String")
        val ref2 = solver.tryToSolveType("java.lang.String")

        assertTrue(ref1.isSolved)
        assertTrue(ref2.isSolved)
        assertEquals(ref1.getDeclaration(), ref2.getDeclaration())
    }

    @Test
    fun `add solver increments count`() {
        val solver = CombinedTypeSolver()
        assertEquals(0, solver.getSolverCount())

        solver.add(ReflectionTypeSolver())
        assertEquals(1, solver.getSolverCount())

        solver.add(ReflectionTypeSolver(jreOnly = false))
        assertEquals(2, solver.getSolverCount())
    }

    @Test
    fun `clearCache clears the cache`() {
        val solver = CombinedTypeSolver(ReflectionTypeSolver())

        solver.tryToSolveType("java.lang.String") // Populate cache
        solver.clearCache()

        // Should still resolve after cache clear
        val ref = solver.tryToSolveType("java.lang.String")
        assertTrue(ref.isSolved)
    }

    @Test
    fun `sets parent on child solvers`() {
        val childSolver = ReflectionTypeSolver()
        val combined = CombinedTypeSolver(childSolver)

        assertEquals(combined, childSolver.parent)
    }
}
