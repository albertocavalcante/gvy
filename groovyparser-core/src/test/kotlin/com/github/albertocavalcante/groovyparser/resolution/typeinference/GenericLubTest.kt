package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericLubTest {

    private val typeSolver = CombinedTypeSolver(ReflectionTypeSolver())

    @Test
    fun `lub of List of String and List of Integer should be List of Object`() {
        val listRef = typeSolver.tryToSolveType("java.util.List").getDeclaration()
        val stringType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.String").getDeclaration())
        val integerType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.Integer").getDeclaration())

        val listString = ResolvedReferenceType(listRef, listOf(stringType))
        val listInteger = ResolvedReferenceType(listRef, listOf(integerType))

        val result = LeastUpperBoundLogic.lub(listOf(listString, listInteger), typeSolver)

        assertTrue(result.isReferenceType())
        assertEquals("java.util.List", result.asReferenceType().declaration.qualifiedName)

        val typeArgs = result.asReferenceType().typeArguments
        assertEquals(1, typeArgs.size, "Expected 1 type argument for List")
        // LUB(String, Integer) -> Comparable (since both implement it)
        assertEquals("java.lang.Comparable", typeArgs[0].asReferenceType().declaration.qualifiedName)
    }

    @Test
    fun `lub of Map String-Integer and Map String-Double should be Map String-Number`() {
        val mapRef = typeSolver.tryToSolveType("java.util.Map").getDeclaration()
        val stringType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.String").getDeclaration())
        val intType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.Integer").getDeclaration())
        val doubleType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.Double").getDeclaration())

        val map1 = ResolvedReferenceType(mapRef, listOf(stringType, intType))
        val map2 = ResolvedReferenceType(mapRef, listOf(stringType, doubleType))

        val result = LeastUpperBoundLogic.lub(listOf(map1, map2), typeSolver)

        assertTrue(result.isReferenceType())
        assertEquals("java.util.Map", result.asReferenceType().declaration.qualifiedName)

        val typeArgs = result.asReferenceType().typeArguments
        assertEquals(2, typeArgs.size)
        assertEquals("java.lang.String", typeArgs[0].asReferenceType().declaration.qualifiedName)
        assertEquals("java.lang.Number", typeArgs[1].asReferenceType().declaration.qualifiedName)
    }

    @Test
    fun `lub of Closure returning Integer and Double should be Closure returning Number`() {
        val closureRef = typeSolver.tryToSolveType("groovy.lang.Closure").getDeclaration()
        val intType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.Integer").getDeclaration())
        val doubleType = ResolvedReferenceType(typeSolver.tryToSolveType("java.lang.Double").getDeclaration())

        val cl1 = ResolvedReferenceType(closureRef, listOf(intType))
        val cl2 = ResolvedReferenceType(closureRef, listOf(doubleType))

        val result = LeastUpperBoundLogic.lub(listOf(cl1, cl2), typeSolver)

        assertTrue(result.isReferenceType())
        assertEquals("groovy.lang.Closure", result.asReferenceType().declaration.qualifiedName)

        val typeArgs = result.asReferenceType().typeArguments
        assertEquals(1, typeArgs.size)
        assertEquals("java.lang.Number", typeArgs[0].asReferenceType().declaration.qualifiedName)
    }
}
