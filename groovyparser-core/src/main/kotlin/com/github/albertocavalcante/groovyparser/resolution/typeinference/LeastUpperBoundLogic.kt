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
 *
 * This implementation includes Groovy-specific enhancements:
 * - GString + String = String
 * - BigInteger/BigDecimal in numeric hierarchy
 * - Improved common ancestor selection
 */
object LeastUpperBoundLogic {

    // Groovy type constants
    private const val JAVA_LANG_STRING = "java.lang.String"
    private const val JAVA_LANG_OBJECT = "java.lang.Object"
    private const val JAVA_LANG_NUMBER = "java.lang.Number"
    private const val JAVA_MATH_BIG_INTEGER = "java.math.BigInteger"
    private const val JAVA_MATH_BIG_DECIMAL = "java.math.BigDecimal"
    private const val GROOVY_LANG_GSTRING = "groovy.lang.GString"
    private const val JAVA_LANG_CHARSEQUENCE = "java.lang.CharSequence"
    private const val JAVA_LANG_COMPARABLE = "java.lang.Comparable"

    // Numeric type precedence including BigInteger/BigDecimal
    // Higher number = wider type (wins in LUB)
    private val NUMERIC_RANK = mapOf(
        "java.lang.Byte" to 1, "byte" to 1,
        "java.lang.Character" to 2, "char" to 2,
        "java.lang.Short" to 3, "short" to 3,
        "java.lang.Integer" to 4, "int" to 4,
        "java.lang.Long" to 5, "long" to 5,
        JAVA_MATH_BIG_INTEGER to 6,
        JAVA_MATH_BIG_DECIMAL to 7,
        "java.lang.Float" to 8, "float" to 8,
        "java.lang.Double" to 9, "double" to 9,
    )

    // Preferred types when multiple common ancestors exist
    // Lower number = more preferred
    private val INTERFACE_PRIORITY = mapOf(
        "java.util.List" to 1,
        "java.util.Set" to 1,
        "java.util.Map" to 1,
        "java.util.Collection" to 2,
        "java.lang.Iterable" to 3,
        JAVA_LANG_CHARSEQUENCE to 4,
        JAVA_LANG_COMPARABLE to 5,
        "java.io.Serializable" to 10,
        JAVA_LANG_OBJECT to 100,
    )

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

        // Check for GString + String special case
        val gstringLub = checkGStringLub(nonNullTypes, typeSolver)
        if (gstringLub != null) return gstringLub

        // Check for numeric LUB (including BigInteger/BigDecimal)
        val numericLub = checkNumericLub(nonNullTypes, typeSolver)
        if (numericLub != null) return numericLub

        // All primitives (non-numeric like boolean)?
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
     * Convenience method for computing LUB of two types.
     */
    fun lub(type1: ResolvedType, type2: ResolvedType, typeSolver: TypeSolver): ResolvedType =
        lub(listOf(type1, type2), typeSolver)

    /**
     * Computes LUB allowing null types, which are filtered out.
     * Returns null if all types are null.
     */
    fun lubNullable(types: List<ResolvedType?>, typeSolver: TypeSolver): ResolvedType? {
        val nonNull = types.filterNotNull()
        return if (nonNull.isEmpty()) null else lub(nonNull, typeSolver)
    }

    /**
     * Checks for GString + String = String pattern.
     * In Groovy, when you concatenate a GString with a String, the result is String.
     */
    private fun checkGStringLub(types: List<ResolvedType>, typeSolver: TypeSolver): ResolvedType? {
        if (types.size != 2) return null

        val names = types.mapNotNull {
            if (it.isReferenceType()) it.asReferenceType().declaration.qualifiedName else null
        }.toSet()

        if (names == setOf(JAVA_LANG_STRING, GROOVY_LANG_GSTRING)) {
            return resolveType(JAVA_LANG_STRING, typeSolver)
        }

        return null
    }

    /**
     * Checks for numeric types including BigInteger and BigDecimal.
     * Uses Groovy's numeric promotion rules.
     */
    private fun checkNumericLub(types: List<ResolvedType>, typeSolver: TypeSolver): ResolvedType? {
        // Track if all inputs are primitives (to return primitive) or mixed (boxed)
        var allPrimitives = true

        val ranks = types.map { type ->
            when {
                type.isPrimitive() -> {
                    val primitive = type.asPrimitive()
                    // Map primitive to its rank
                    when (primitive) {
                        ResolvedPrimitiveType.BYTE -> 1
                        ResolvedPrimitiveType.CHAR -> 2
                        ResolvedPrimitiveType.SHORT -> 3
                        ResolvedPrimitiveType.INT -> 4
                        ResolvedPrimitiveType.LONG -> 5
                        ResolvedPrimitiveType.FLOAT -> 8
                        ResolvedPrimitiveType.DOUBLE -> 9
                        ResolvedPrimitiveType.BOOLEAN -> null // Can't promote boolean
                    }
                }

                type.isReferenceType() -> {
                    allPrimitives = false
                    NUMERIC_RANK[type.asReferenceType().declaration.qualifiedName]
                }

                else -> null
            }
        }

        // If any type is not numeric, can't use numeric LUB
        if (ranks.any { it == null }) return null

        // Find highest rank
        val maxRank = ranks.filterNotNull().maxOrNull() ?: return null

        // For primitives, return the primitive type directly
        if (allPrimitives) {
            return when (maxRank) {
                1 -> ResolvedPrimitiveType.BYTE
                2 -> ResolvedPrimitiveType.CHAR
                3 -> ResolvedPrimitiveType.SHORT
                4 -> ResolvedPrimitiveType.INT
                5 -> ResolvedPrimitiveType.LONG
                8 -> ResolvedPrimitiveType.FLOAT
                9 -> ResolvedPrimitiveType.DOUBLE
                else -> null
            }
        }

        // For BigInteger/BigDecimal (ranks 6, 7), return reference types
        return when (maxRank) {
            6 -> resolveType(JAVA_MATH_BIG_INTEGER, typeSolver)
            7 -> resolveType(JAVA_MATH_BIG_DECIMAL, typeSolver)
            else -> null // Mixed primitive/reference handled elsewhere
        }
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
     * Uses improved heuristics to select the best common ancestor.
     */
    private fun findCommonAncestor(types: List<ResolvedReferenceType>, typeSolver: TypeSolver): ResolvedType {
        if (types.isEmpty()) return getObjectType(typeSolver)
        if (types.size == 1) return types.first()

        // Get all ancestors for each type (including the type itself)
        val ancestorSets = types.map { type ->
            val set = mutableSetOf(type.declaration.qualifiedName)
            set.addAll(getAllAncestorNames(type))
            set
        }

        // Find intersection of all ancestor sets
        val commonAncestors = ancestorSets.reduce { acc, set ->
            acc.intersect(set).toMutableSet()
        }

        if (commonAncestors.isEmpty()) {
            return getObjectType(typeSolver)
        }

        // Select best common ancestor using priority
        val bestAncestor = selectBestAncestor(commonAncestors)

        val ref = typeSolver.tryToSolveType(bestAncestor)
        return if (ref.isSolved) {
            ResolvedReferenceType(ref.getDeclaration())
        } else {
            getObjectType(typeSolver)
        }
    }

    /**
     * Selects the best common ancestor from a set of candidates.
     * Prefers concrete classes > well-known interfaces > Object.
     */
    private fun selectBestAncestor(candidates: Set<String>): String {
        // Filter out Object as a last resort
        val nonObject = candidates - JAVA_LANG_OBJECT

        if (nonObject.isEmpty()) {
            return JAVA_LANG_OBJECT
        }

        // Sort by interface priority (lower is better)
        // Types not in priority map get priority 50 (moderate)
        return nonObject.minByOrNull { INTERFACE_PRIORITY[it] ?: 50 }
            ?: JAVA_LANG_OBJECT
    }

    private fun getAllAncestorNames(type: ResolvedReferenceType): Set<String> {
        val ancestors = mutableSetOf<String>()
        val toProcess = mutableListOf<ResolvedReferenceType>()
        toProcess.addAll(type.declaration.getAncestors())

        while (toProcess.isNotEmpty()) {
            val current = toProcess.removeAt(0)
            val name = current.declaration.qualifiedName
            if (name !in ancestors) {
                ancestors.add(name)
                toProcess.addAll(current.declaration.getAncestors())
            }
        }

        return ancestors
    }

    private fun getObjectType(typeSolver: TypeSolver): ResolvedType =
        resolveType(JAVA_LANG_OBJECT, typeSolver) ?: ResolvedPrimitiveType.INT

    private fun resolveType(name: String, typeSolver: TypeSolver): ResolvedType? {
        val ref = typeSolver.tryToSolveType(name)
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else null
    }
}
