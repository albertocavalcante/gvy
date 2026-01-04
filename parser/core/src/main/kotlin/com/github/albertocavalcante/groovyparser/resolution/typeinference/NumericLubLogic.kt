package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import com.github.albertocavalcante.groovyparser.resolution.types.asPrimitive
import com.github.albertocavalcante.groovyparser.resolution.types.asReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.isPrimitive
import com.github.albertocavalcante.groovyparser.resolution.types.isReferenceType

internal object NumericLubLogic {

    private const val JAVA_MATH_BIG_INTEGER = "java.math.BigInteger"
    private const val JAVA_MATH_BIG_DECIMAL = "java.math.BigDecimal"

    // Numeric type precedence ranks
    private const val RANK_BYTE = 1
    private const val RANK_CHAR = 2
    private const val RANK_SHORT = 3
    private const val RANK_INT = 4
    private const val RANK_LONG = 5
    private const val RANK_BIG_INTEGER = 6
    private const val RANK_BIG_DECIMAL = 7
    private const val RANK_FLOAT = 8
    private const val RANK_DOUBLE = 9

    // Numeric precedence for promoteNumericTypes (different ranking system)
    private const val PRECEDENCE_BOOLEAN = 0
    private const val PRECEDENCE_BYTE = 1
    private const val PRECEDENCE_CHAR = 2
    private const val PRECEDENCE_SHORT = 2 // Same as char in promotion hierarchy
    private const val PRECEDENCE_INT = 3
    private const val PRECEDENCE_LONG = 4
    private const val PRECEDENCE_FLOAT = 5
    private const val PRECEDENCE_DOUBLE = 6

    private val NUMERIC_RANK = mapOf(
        "java.lang.Byte" to RANK_BYTE, "byte" to RANK_BYTE,
        "java.lang.Character" to RANK_CHAR, "char" to RANK_CHAR,
        "java.lang.Short" to RANK_SHORT, "short" to RANK_SHORT,
        "java.lang.Integer" to RANK_INT, "int" to RANK_INT,
        "java.lang.Long" to RANK_LONG, "long" to RANK_LONG,
        JAVA_MATH_BIG_INTEGER to RANK_BIG_INTEGER,
        JAVA_MATH_BIG_DECIMAL to RANK_BIG_DECIMAL,
        "java.lang.Float" to RANK_FLOAT, "float" to RANK_FLOAT,
        "java.lang.Double" to RANK_DOUBLE, "double" to RANK_DOUBLE,
    )

    fun checkNumericLub(types: List<ResolvedType>, typeSolver: TypeSolver): ResolvedType? {
        // Map types to their ranks
        val ranks = types.map { getRank(it) }

        // If any type is not numeric, can't use numeric LUB
        if (ranks.any { it == null }) return null

        val validRanks = ranks.filterNotNull()
        val maxRank = validRanks.maxOrNull() ?: return null
        val allPrimitives = types.all { it.isPrimitive() }

        // For primitives, return the primitive type directly if all can widen to it
        return if (allPrimitives) {
            getPrimitiveLub(maxRank, types)
        } else {
            // For BigInteger/BigDecimal or mixed types, return reference types
            getReferenceLub(maxRank, typeSolver)
        }
    }

    fun promoteNumericTypes(primitives: List<ResolvedPrimitiveType>): ResolvedPrimitiveType {
        require(primitives.none { it == ResolvedPrimitiveType.BOOLEAN }) { "Cannot compute LUB involving boolean" }

        val widest = primitives.maxByOrNull { getNumericPrecedence(it) } ?: ResolvedPrimitiveType.INT

        // Handling mixed types that don't widen to each other (byte/char -> int)
        if (primitives.size > 1 && isSmallNumeric(widest)) {
            if (primitives.any { !widest.isAssignableBy(it) }) {
                return ResolvedPrimitiveType.INT
            }
        }

        return widest
    }

    private fun getRank(type: ResolvedType): Int? = when {
        type.isPrimitive() -> getPrimitiveRank(type.asPrimitive())
        type.isReferenceType() -> NUMERIC_RANK[type.asReferenceType().declaration.qualifiedName]
        else -> null
    }

    private fun getPrimitiveRank(primitive: ResolvedPrimitiveType): Int? = when (primitive) {
        ResolvedPrimitiveType.BYTE -> RANK_BYTE
        ResolvedPrimitiveType.CHAR -> RANK_CHAR
        ResolvedPrimitiveType.SHORT -> RANK_SHORT
        ResolvedPrimitiveType.INT -> RANK_INT
        ResolvedPrimitiveType.LONG -> RANK_LONG
        ResolvedPrimitiveType.FLOAT -> RANK_FLOAT
        ResolvedPrimitiveType.DOUBLE -> RANK_DOUBLE
        ResolvedPrimitiveType.BOOLEAN -> null
    }

    private fun getPrimitiveLub(maxRank: Int, types: List<ResolvedType>): ResolvedType? {
        val maxType = when (maxRank) {
            RANK_BYTE -> ResolvedPrimitiveType.BYTE
            RANK_CHAR -> ResolvedPrimitiveType.CHAR
            RANK_SHORT -> ResolvedPrimitiveType.SHORT
            RANK_INT -> ResolvedPrimitiveType.INT
            RANK_LONG -> ResolvedPrimitiveType.LONG
            RANK_FLOAT -> ResolvedPrimitiveType.FLOAT
            RANK_DOUBLE -> ResolvedPrimitiveType.DOUBLE
            else -> null
        } ?: return null

        return if (types.all { maxType.isAssignableBy(it) }) maxType else null
    }

    private fun getReferenceLub(maxRank: Int, typeSolver: TypeSolver): ResolvedType? = when (maxRank) {
        RANK_BIG_INTEGER -> resolveType(JAVA_MATH_BIG_INTEGER, typeSolver)
        RANK_BIG_DECIMAL -> resolveType(JAVA_MATH_BIG_DECIMAL, typeSolver)
        else -> null
    }

    private fun getNumericPrecedence(type: ResolvedPrimitiveType): Int = when (type) {
        ResolvedPrimitiveType.BYTE -> PRECEDENCE_BYTE
        ResolvedPrimitiveType.SHORT -> PRECEDENCE_SHORT
        ResolvedPrimitiveType.CHAR -> PRECEDENCE_CHAR
        ResolvedPrimitiveType.INT -> PRECEDENCE_INT
        ResolvedPrimitiveType.LONG -> PRECEDENCE_LONG
        ResolvedPrimitiveType.FLOAT -> PRECEDENCE_FLOAT
        ResolvedPrimitiveType.DOUBLE -> PRECEDENCE_DOUBLE
        ResolvedPrimitiveType.BOOLEAN -> PRECEDENCE_BOOLEAN
    }

    private fun isSmallNumeric(type: ResolvedPrimitiveType) = type in setOf(
        ResolvedPrimitiveType.BYTE,
        ResolvedPrimitiveType.SHORT,
        ResolvedPrimitiveType.CHAR,
    )

    private fun resolveType(name: String, typeSolver: TypeSolver): ResolvedType? {
        val ref = typeSolver.tryToSolveType(name)
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else null
    }
}
