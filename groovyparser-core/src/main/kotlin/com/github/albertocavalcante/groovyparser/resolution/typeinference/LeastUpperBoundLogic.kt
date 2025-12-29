package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedNullType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Implements the Least Upper Bound (LUB) algorithm for type inference.
 *
 * The LUB of a set of types is the most specific type that is a supertype of all.
 * For example:
 * - LUB(Integer, Long) = Number
 * - LUB(ArrayList, LinkedList) = List
 * - LUB(String, null) = String
 */
object LeastUpperBoundLogic {

    /**
     * Computes the least upper bound of a list of types.
     *
     * @param types The types to find the LUB of
     * @param typeSolver The type solver for resolving common supertypes
     * @return The least upper bound type
     * @throws IllegalArgumentException if the list is empty
     */
    fun lub(types: List<ResolvedType>, typeSolver: TypeSolver): ResolvedType {
        require(types.isNotEmpty()) { "Cannot compute LUB of empty list" }

        if (types.size == 1) return types.first()

        // Remove nulls
        val nonNullTypes = types.filter { !it.isNull() }
        if (nonNullTypes.isEmpty()) return ResolvedNullType
        if (nonNullTypes.size == 1) return nonNullTypes.first()

        // All same type?
        if (nonNullTypes.all { it == nonNullTypes.first() }) {
            return nonNullTypes.first()
        }

        // All primitives?
        if (nonNullTypes.all { it.isPrimitive() }) {
            return promoteNumericTypes(nonNullTypes.map { it.asPrimitive() })
        }

        // Find common ancestors for reference types
        val referenceTypes = nonNullTypes.filter { it.isReferenceType() }
        if (referenceTypes.size == nonNullTypes.size) {
            return findCommonAncestor(referenceTypes.map { it.asReferenceType() }, typeSolver)
        }

        // Mixed types - fall back to Object
        return getObjectType(typeSolver)
    }

    /**
     * Promotes a list of numeric primitive types to their common type.
     */
    private fun promoteNumericTypes(primitives: List<ResolvedPrimitiveType>): ResolvedPrimitiveType {
        // Boolean can't be promoted
        if (primitives.any { it == ResolvedPrimitiveType.BOOLEAN }) {
            throw IllegalArgumentException("Cannot compute LUB involving boolean")
        }

        // Find the widest type
        return primitives.maxByOrNull { getNumericPrecedence(it) }
            ?: ResolvedPrimitiveType.INT
    }

    private fun getNumericPrecedence(type: ResolvedPrimitiveType): Int = when (type) {
        ResolvedPrimitiveType.BYTE -> 1
        ResolvedPrimitiveType.SHORT -> 2
        ResolvedPrimitiveType.CHAR -> 2
        ResolvedPrimitiveType.INT -> 3
        ResolvedPrimitiveType.LONG -> 4
        ResolvedPrimitiveType.FLOAT -> 5
        ResolvedPrimitiveType.DOUBLE -> 6
        ResolvedPrimitiveType.BOOLEAN -> 0
    }

    /**
     * Finds the common ancestor of reference types.
     */
    private fun findCommonAncestor(types: List<ResolvedReferenceType>, typeSolver: TypeSolver): ResolvedType {
        if (types.isEmpty()) return getObjectType(typeSolver)
        if (types.size == 1) return types.first()

        // Get all ancestors for the first type
        val firstType = types.first()
        val firstAncestors = getAllAncestors(firstType)
        val ancestorSet = mutableSetOf(firstType.declaration.qualifiedName)
        ancestorSet.addAll(firstAncestors.map { it.declaration.qualifiedName })

        // Find common ancestors by intersecting
        for (type in types.drop(1)) {
            val typeAncestors = getAllAncestors(type)
            val typeAncestorNames = mutableSetOf(type.declaration.qualifiedName)
            typeAncestorNames.addAll(typeAncestors.map { it.declaration.qualifiedName })
            ancestorSet.retainAll(typeAncestorNames)
        }

        // Find the most specific common ancestor
        // Prefer non-Object types
        val nonObjectAncestors = ancestorSet - "java.lang.Object"
        val bestAncestor = if (nonObjectAncestors.isNotEmpty()) {
            nonObjectAncestors.first()
        } else {
            "java.lang.Object"
        }

        val ref = typeSolver.tryToSolveType(bestAncestor)
        return if (ref.isSolved) {
            ResolvedReferenceType(ref.getDeclaration())
        } else {
            getObjectType(typeSolver)
        }
    }

    private fun getAllAncestors(type: ResolvedReferenceType): List<ResolvedReferenceType> {
        val ancestors = mutableListOf<ResolvedReferenceType>()
        val toProcess = mutableListOf<ResolvedReferenceType>()
        toProcess.addAll(type.declaration.getAncestors())

        while (toProcess.isNotEmpty()) {
            val current = toProcess.removeAt(0)
            if (ancestors.none { it.declaration.qualifiedName == current.declaration.qualifiedName }) {
                ancestors.add(current)
                toProcess.addAll(current.declaration.getAncestors())
            }
        }

        return ancestors
    }

    private fun getObjectType(typeSolver: TypeSolver): ResolvedType {
        val ref = typeSolver.tryToSolveType("java.lang.Object")
        return if (ref.isSolved) {
            ResolvedReferenceType(ref.getDeclaration())
        } else {
            // Shouldn't happen in practice
            ResolvedPrimitiveType.INT
        }
    }
}
