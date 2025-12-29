package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedNullType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeastUpperBoundLogicTest {

    private val typeSolver = CombinedTypeSolver(ReflectionTypeSolver())

    @Test
    fun `lub of single type returns that type`() {
        val result = LeastUpperBoundLogic.lub(listOf(ResolvedPrimitiveType.INT), typeSolver)
        assertEquals(ResolvedPrimitiveType.INT, result)
    }

    @Test
    fun `lub of same types returns that type`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.INT),
            typeSolver,
        )
        assertEquals(ResolvedPrimitiveType.INT, result)
    }

    @Test
    fun `lub of int and long is long`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.LONG),
            typeSolver,
        )
        assertEquals(ResolvedPrimitiveType.LONG, result)
    }

    @Test
    fun `lub of int and double is double`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.DOUBLE),
            typeSolver,
        )
        assertEquals(ResolvedPrimitiveType.DOUBLE, result)
    }

    @Test
    fun `lub of float and double is double`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedPrimitiveType.FLOAT, ResolvedPrimitiveType.DOUBLE),
            typeSolver,
        )
        assertEquals(ResolvedPrimitiveType.DOUBLE, result)
    }

    @Test
    fun `lub of byte and short is int`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedPrimitiveType.BYTE, ResolvedPrimitiveType.SHORT),
            typeSolver,
        )
        // byte and short promote to int in arithmetic
        assertTrue(result == ResolvedPrimitiveType.SHORT || result == ResolvedPrimitiveType.INT)
    }

    @Test
    fun `lub with null type returns the other type`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedPrimitiveType.INT, ResolvedNullType),
            typeSolver,
        )
        assertEquals(ResolvedPrimitiveType.INT, result)
    }

    @Test
    fun `lub of only null types returns null`() {
        val result = LeastUpperBoundLogic.lub(
            listOf(ResolvedNullType, ResolvedNullType),
            typeSolver,
        )
        assertEquals(ResolvedNullType, result)
    }

    @Test
    fun `lub of reference types finds common ancestor`() {
        val stringRef = typeSolver.tryToSolveType("java.lang.String")
        val integerRef = typeSolver.tryToSolveType("java.lang.Integer")

        assertTrue(stringRef.isSolved)
        assertTrue(integerRef.isSolved)

        val stringType = ResolvedReferenceType(stringRef.getDeclaration())
        val integerType = ResolvedReferenceType(integerRef.getDeclaration())

        val result = LeastUpperBoundLogic.lub(listOf(stringType, integerType), typeSolver)

        // Common ancestor should be Object
        assertTrue(result.isReferenceType())
    }

    @Test
    fun `lub of empty list throws exception`() {
        assertThrows<IllegalArgumentException> {
            LeastUpperBoundLogic.lub(emptyList(), typeSolver)
        }
    }

    @Test
    fun `lub with boolean throws for numeric promotion`() {
        assertThrows<IllegalArgumentException> {
            LeastUpperBoundLogic.lub(
                listOf(ResolvedPrimitiveType.BOOLEAN, ResolvedPrimitiveType.INT),
                typeSolver,
            )
        }
    }
}
