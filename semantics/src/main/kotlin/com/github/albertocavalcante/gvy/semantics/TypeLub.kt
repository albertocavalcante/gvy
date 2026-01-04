package com.github.albertocavalcante.gvy.semantics

/**
 * Logic for computing Least Upper Bound (LUB) of SemanticTypes.
 * Ported from LeastUpperBoundLogic.kt but adapted for SemanticType (no TypeSolver dependency).
 */
object TypeLub {

    /**
     * Compute Least Upper Bound of multiple types.
     */
    fun lub(types: List<SemanticType>): SemanticType {
        require(types.isNotEmpty()) { "Cannot compute LUB of empty list" }

        val nonNullTypes = types.filter { it != SemanticType.Null }

        // Early returns for single types or all same type
        if (nonNullTypes.isEmpty()) {
            return SemanticType.Null
        }
        if (nonNullTypes.size == 1) {
            return nonNullTypes.first()
        }
        if (nonNullTypes.all { it == nonNullTypes.first() }) {
            return nonNullTypes.first()
        }

        return computeComplexLub(nonNullTypes)
    }

    /**
     * Convenience for computing LUB of two types.
     *
     * @param type1 The first type
     * @param type2 The second type
     * @return The LUB of the two types
     */
    fun lub(type1: SemanticType, type2: SemanticType): SemanticType = lub(listOf(type1, type2))

    /**
     * Internal method to resolve LUB for non-trivial cases.
     * Tries strategies in order:
     * 1. Groovy-specific GString logic
     * 2. Numeric promotion
     * 3. Fallback to common ancestor inference
     */
    private fun computeComplexLub(types: List<SemanticType>): SemanticType = checkGStringLub(types)
        ?: checkNumericLub(types)
        ?: computeFallbackLub(types)

    /**
     * Handles specific Groovy rule: GString + String = String.
     */
    private fun checkGStringLub(types: List<SemanticType>): SemanticType? {
        if (types.size != 2) return null

        val names = types.mapNotNull {
            if (it is SemanticType.Known) it.fqn else null
        }.toSet()

        if (names == setOf(TypeConstants.STRING.fqn, TypeConstants.GSTRING.fqn)) {
            return TypeConstants.STRING
        }

        return null
    }

    /**
     * Checks if types can form a valid numeric LUB.
     * Handles mixed primitives and BigInteger/BigDecimal promotion.
     */
    private fun checkNumericLub(types: List<SemanticType>): SemanticType? {
        val ranks = types.map { type ->
            getNumericRank(type)
        }

        // If any type is not numeric, can't use numeric LUB
        if (ranks.any { it == null }) return null

        val validRanks = ranks.filterNotNull()
        val maxRank = validRanks.maxOrNull() ?: return null

        // Check if all are primitives
        val allPrimitives = types.all { it is SemanticType.Primitive }

        if (allPrimitives) {
            val primitives = types.filterIsInstance<SemanticType.Primitive>()
            // Optimize for pure primitives: use standard numeric promotion.
            return promoteNumeric(primitives)
        }

        // Mixed primitives and wrappers/Big*
        return when (maxRank) {
            RANK_BIG_INTEGER -> TypeConstants.BIG_INTEGER
            RANK_BIG_DECIMAL -> TypeConstants.BIG_DECIMAL
            RANK_DOUBLE -> TypeConstants.DOUBLE // Or java.lang.Double?
            // Standard Java/Groovy promotion ranks
            // Note: Groovy promotes mixed types to the widest type, often preferring Double or BigDecimal
            RANK_BYTE -> TypeConstants.BYTE
            RANK_CHAR -> TypeConstants.CHAR
            RANK_SHORT -> TypeConstants.SHORT
            RANK_INT -> TypeConstants.INT
            RANK_LONG -> TypeConstants.LONG
            RANK_FLOAT -> TypeConstants.FLOAT
            else -> null
        }?.let {
            // Handle mixed primitive/reference cases (e.g. Integer + int -> int, or BigInteger + Double -> BigDecimal)
            if (maxRank >= RANK_BIG_INTEGER) {
                if (maxRank == RANK_BIG_INTEGER) TypeConstants.BIG_INTEGER else TypeConstants.BIG_DECIMAL
            } else {
                null
            }
        }
    }

    /**
     * Promote numeric primitives following Java/Groovy rules.
     */
    fun promoteNumeric(types: List<SemanticType.Primitive>): SemanticType.Primitive {
        require(types.none { it.kind == PrimitiveKind.BOOLEAN }) { "Cannot compute LUB involving boolean" }

        val widest = types.maxByOrNull { getNumericPrecedence(it.kind) }
            ?: SemanticType.Primitive(PrimitiveKind.INT)

        val widestKind = widest.kind

        // Byte/Short/Char promote to Int at minimum
        if (types.size > 1 && widestKind in listOf(PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.CHAR)) {
            return SemanticType.Primitive(PrimitiveKind.INT)
        }

        return widest
    }

    /**
     * Fallback strategy when specific rules don't apply.
     * - If all primitives (including boolean), finds common primitive or Object.
     * - If references involved, finds common class/interface ancestor.
     */
    private fun computeFallbackLub(types: List<SemanticType>): SemanticType {
        if (types.all { it is SemanticType.Primitive }) {
            // These satisfy isPrimitive, so we cast safely
            @Suppress("UNCHECKED_CAST")
            val primitives = types as List<SemanticType.Primitive>

            if (primitives.any { it.kind == PrimitiveKind.BOOLEAN }) {
                if (primitives.all { it.kind == PrimitiveKind.BOOLEAN }) {
                    return SemanticType.Primitive(PrimitiveKind.BOOLEAN)
                }
                return TypeConstants.OBJECT
            }

            return promoteNumeric(primitives)
        }

        // Mixed or Reference types
        val referenceTypes = types.mapNotNull {
            when (it) {
                is SemanticType.Known -> it
                is SemanticType.Primitive -> {
                    // Primitives in a mixed context (e.g. int + String) are treated as objects
                    // for the purpose of finding a common reference ancestor.
                    null
                }

                is SemanticType.Dynamic -> TypeConstants.OBJECT // Treat dynamic as Object for LUB?
                else -> null
            }
        }

        if (referenceTypes.size == types.size) {
            // All are known/reference types
            return findCommonAncestor(referenceTypes)
        }

        return TypeConstants.OBJECT
    }

    // --- Hardcoded Hierarchy Logic (since no TypeSolver) ---

    // Parent -> Children (inverted for easier lookup? No, Child -> Parents is better)
    private val HARDCODED_PARENTS = mapOf(
        "java.util.ArrayList" to setOf(
            "java.util.List",
            "java.util.RandomAccess",
            "java.lang.Cloneable",
            "java.io.Serializable",
        ),
        "java.util.LinkedList" to setOf(
            "java.util.List",
            "java.util.Deque",
            "java.lang.Cloneable",
            "java.io.Serializable",
        ),
        "java.util.List" to setOf("java.util.Collection"),
        "java.util.Set" to setOf("java.util.Collection"),
        "java.util.Collection" to setOf("java.lang.Iterable"),
        "java.lang.Integer" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Long" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Double" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Float" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Byte" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Short" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.math.BigInteger" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.math.BigDecimal" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.String" to setOf("java.lang.CharSequence", "java.lang.Comparable", "java.io.Serializable"),
        "groovy.lang.GString" to setOf(
            "java.lang.CharSequence",
            "groovy.lang.GroovyObject",
            "groovy.lang.Writable",
            "java.io.Serializable",
            "java.lang.Comparable",
        ),
        "java.lang.Number" to setOf("java.io.Serializable"),
    )

    private fun getAllAncestors(fqn: String): Set<String> {
        val parents = HARDCODED_PARENTS[fqn] ?: return emptySet()
        val result = parents.toMutableSet()
        for (parent in parents) {
            result.addAll(getAllAncestors(parent))
        }
        // Always add Object
        result.add("java.lang.Object")
        return result
    }

    private fun findCommonAncestor(types: List<SemanticType.Known>): SemanticType {
        if (types.isEmpty()) return TypeConstants.OBJECT

        val ancestorSets = types.map { type ->
            val set = mutableSetOf(type.fqn)
            set.addAll(getAllAncestors(type.fqn))
            set
        }

        val commonAncestors = ancestorSets.reduce { acc, set ->
            acc.intersect(set).toMutableSet()
        }

        if (commonAncestors.isEmpty()) return TypeConstants.OBJECT

        // Select best
        val best = selectBestAncestor(commonAncestors)
        return SemanticType.Known(best)
    }

    // Priority map
    private val INTERFACE_PRIORITY = mapOf(
        "java.util.List" to 1,
        "java.util.Set" to 1,
        "java.util.Map" to 1,
        "java.lang.Number" to 1,
        "java.util.Collection" to 2,
        "java.lang.Iterable" to 3,
        "java.lang.CharSequence" to 4,
        "java.lang.Comparable" to 5,
        "java.io.Serializable" to 10,
        "java.lang.Object" to 100,
    )

    private fun selectBestAncestor(candidates: Set<String>): String {
        val nonObject = candidates - "java.lang.Object"
        if (nonObject.isEmpty()) return "java.lang.Object"

        return nonObject.minByOrNull { INTERFACE_PRIORITY[it] ?: 50 }
            ?: "java.lang.Object"
    }

    // --- Helpers ---

    private const val RANK_BYTE = 1
    private const val RANK_CHAR = 2
    private const val RANK_SHORT = 3
    private const val RANK_INT = 4
    private const val RANK_LONG = 5
    private const val RANK_BIG_INTEGER = 6
    private const val RANK_BIG_DECIMAL = 7
    private const val RANK_FLOAT = 8
    private const val RANK_DOUBLE = 9

    private fun getNumericRank(type: SemanticType): Int? = when (type) {
        is SemanticType.Primitive -> when (type.kind) {
            PrimitiveKind.BYTE -> RANK_BYTE
            PrimitiveKind.CHAR -> RANK_CHAR
            PrimitiveKind.SHORT -> RANK_SHORT
            PrimitiveKind.INT -> RANK_INT
            PrimitiveKind.LONG -> RANK_LONG
            PrimitiveKind.FLOAT -> RANK_FLOAT
            PrimitiveKind.DOUBLE -> RANK_DOUBLE
            PrimitiveKind.BOOLEAN -> null
        }

        is SemanticType.Known -> when (type.fqn) {
            "java.lang.Byte" -> RANK_BYTE
            "java.lang.Character" -> RANK_CHAR
            "java.lang.Short" -> RANK_SHORT
            "java.lang.Integer" -> RANK_INT
            "java.lang.Long" -> RANK_LONG
            "java.math.BigInteger" -> RANK_BIG_INTEGER
            "java.math.BigDecimal" -> RANK_BIG_DECIMAL
            "java.lang.Float" -> RANK_FLOAT
            "java.lang.Double" -> RANK_DOUBLE
            else -> null
        }

        else -> null
    }

    private const val PRECEDENCE_BOOLEAN = 0
    private const val PRECEDENCE_BYTE = 1
    private const val PRECEDENCE_CHAR = 2
    private const val PRECEDENCE_SHORT = 2
    private const val PRECEDENCE_INT = 3
    private const val PRECEDENCE_LONG = 4
    private const val PRECEDENCE_FLOAT = 5
    private const val PRECEDENCE_DOUBLE = 6

    private fun getNumericPrecedence(kind: PrimitiveKind): Int = when (kind) {
        PrimitiveKind.BYTE -> PRECEDENCE_BYTE
        PrimitiveKind.SHORT -> PRECEDENCE_SHORT
        PrimitiveKind.CHAR -> PRECEDENCE_CHAR
        PrimitiveKind.INT -> PRECEDENCE_INT
        PrimitiveKind.LONG -> PRECEDENCE_LONG
        PrimitiveKind.FLOAT -> PRECEDENCE_FLOAT
        PrimitiveKind.DOUBLE -> PRECEDENCE_DOUBLE
        PrimitiveKind.BOOLEAN -> PRECEDENCE_BOOLEAN
    }
}
