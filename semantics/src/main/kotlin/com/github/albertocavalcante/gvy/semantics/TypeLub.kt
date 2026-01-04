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

        val result = when {
            nonNullTypes.isEmpty() -> SemanticType.Null
            nonNullTypes.size == 1 -> nonNullTypes.first()
            nonNullTypes.all { it == nonNullTypes.first() } -> nonNullTypes.first()
            else -> computeComplexLub(nonNullTypes)
        }

        return result
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
     *
     * @param types List of semantic types to check.
     * @return The numeric LUB if applicable, or null if not a numeric promotion case.
     */
    private fun checkNumericLub(types: List<SemanticType>): SemanticType? {
        val ranks = types.map(::getNumericRank)
        if (ranks.any { it == null }) return null

        val rawMaxRank = checkNotNull(ranks.filterNotNull().maxOrNull())

        // Promote byte/short/char to int minimum.
        val maxRank = maxOf(rawMaxRank, RANK_INT)

        val hasBigType = ranks.any { it == RANK_BIG_INTEGER || it == RANK_BIG_DECIMAL }
        val isFloatOrDouble = maxRank == RANK_FLOAT || maxRank == RANK_DOUBLE

        val preferredType = when {
            hasBigType && isFloatOrDouble -> TypeConstants.BIG_DECIMAL
            maxRank == RANK_BIG_INTEGER -> TypeConstants.BIG_INTEGER
            maxRank == RANK_BIG_DECIMAL -> TypeConstants.BIG_DECIMAL
            else -> null
        }

        val hasWrapperWithMaxRank = types.any { it is SemanticType.Known && getNumericRank(it) == maxRank }
        val wrapper = if (hasWrapperWithMaxRank) KNOWN_FOR_RANK[maxRank] else null

        return preferredType ?: wrapper ?: PRIMITIVE_FOR_RANK[maxRank]
    }

    /**
     * Promote numeric primitives following Java/Groovy rules.
     */
    fun promoteNumeric(types: List<SemanticType.Primitive>): SemanticType.Primitive {
        require(types.none { it.kind == PrimitiveKind.BOOLEAN }) {
            "Boolean cannot participate in numeric promotion. Use computeFallbackLub for mixed boolean/numeric types."
        }

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
        val primitiveLub = types
            .takeIf { it.all { t -> t is SemanticType.Primitive } }
            ?.let {
                @Suppress("UNCHECKED_CAST")
                val primitives = it as List<SemanticType.Primitive>

                val hasBoolean = primitives.any { p -> p.kind == PrimitiveKind.BOOLEAN }
                when {
                    hasBoolean && primitives.all { p -> p.kind == PrimitiveKind.BOOLEAN } ->
                        SemanticType.Primitive(PrimitiveKind.BOOLEAN)

                    hasBoolean -> TypeConstants.OBJECT
                    else -> promoteNumeric(primitives)
                }
            }

        if (primitiveLub != null) return primitiveLub

        // Mixed or Reference types
        val referenceTypes = types.mapNotNull {
            // LSP false positive: SemanticType is sealed but not all subclasses matched here
            @Suppress("REDUNDANT_ELSE_IN_WHEN")
            when (it) {
                is SemanticType.Known -> it
                is SemanticType.Primitive -> {
                    // Primitives in mixed context (e.g., int + String) are excluded from
                    // reference type ancestor search. The outer function returns OBJECT.
                    null
                }

                is SemanticType.Dynamic -> TypeConstants.OBJECT // Treat dynamic as Object for LUB?
                else -> null
            }
        }

        return if (referenceTypes.size == types.size) {
            // All are known/reference types
            findCommonAncestor(referenceTypes)
        } else {
            TypeConstants.OBJECT
        }
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
        "java.util.Deque" to setOf("java.util.Collection"),
        "java.lang.Integer" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Long" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Double" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Float" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Byte" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Short" to setOf("java.lang.Number", "java.lang.Comparable"),
        "java.lang.Character" to setOf("java.lang.Comparable"),
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

    private val ANCESTOR_CACHE = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun getAllAncestors(fqn: String): Set<String> {
        return ANCESTOR_CACHE.computeIfAbsent(fqn) { key ->
            val parents = HARDCODED_PARENTS[key] ?: return@computeIfAbsent emptySet()
            val result = parents.toMutableSet()
            for (parent in parents) {
                result.addAll(getAllAncestors(parent))
            }
            // Always add Object
            result.add("java.lang.Object")
            result
        }
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
    private const val PRIORITY_PRIMARY = 1
    private const val PRIORITY_COLLECTION = 2
    private const val PRIORITY_ITERABLE = 3
    private const val PRIORITY_CHAR_SEQUENCE = 4
    private const val PRIORITY_COMPARABLE = 5
    private const val PRIORITY_SERIALIZABLE = 10
    private const val PRIORITY_OBJECT = 100

    private val INTERFACE_PRIORITY = mapOf(
        "java.util.List" to PRIORITY_PRIMARY,
        "java.util.Set" to PRIORITY_PRIMARY,
        "java.util.Map" to PRIORITY_PRIMARY,
        "java.lang.Number" to PRIORITY_PRIMARY,
        "java.util.Collection" to PRIORITY_COLLECTION,
        "java.lang.Iterable" to PRIORITY_ITERABLE,
        "java.lang.CharSequence" to PRIORITY_CHAR_SEQUENCE,
        "java.lang.Comparable" to PRIORITY_COMPARABLE,
        "java.io.Serializable" to PRIORITY_SERIALIZABLE,
        "java.lang.Object" to PRIORITY_OBJECT,
    )

    /** Default priority for types not in INTERFACE_PRIORITY map. */
    private const val DEFAULT_INTERFACE_PRIORITY = 50

    private fun selectBestAncestor(candidates: Set<String>): String {
        val nonObject = candidates - "java.lang.Object"
        if (nonObject.isEmpty()) return "java.lang.Object"

        return nonObject.minByOrNull { INTERFACE_PRIORITY[it] ?: DEFAULT_INTERFACE_PRIORITY }
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

    // Numeric promotion rank (widest type wins)
    // Note: FLOAT (8) and DOUBLE (9) are ranked higher than BIG_DECIMAL (7) because
    // in Groovy, mixed operations often promote to Double unless explicitly coerced.
    // While BigDecimal is "precise", Double is "wider" in terms of range in this model.
    private val PRIMITIVE_RANKS = mapOf(
        PrimitiveKind.BYTE to RANK_BYTE,
        PrimitiveKind.CHAR to RANK_CHAR,
        PrimitiveKind.SHORT to RANK_SHORT,
        PrimitiveKind.INT to RANK_INT,
        PrimitiveKind.LONG to RANK_LONG,
        PrimitiveKind.FLOAT to RANK_FLOAT,
        PrimitiveKind.DOUBLE to RANK_DOUBLE,
    )

    private val WRAPPER_RANKS = mapOf(
        "java.lang.Byte" to RANK_BYTE,
        "java.lang.Character" to RANK_CHAR,
        "java.lang.Short" to RANK_SHORT,
        "java.lang.Integer" to RANK_INT,
        "java.lang.Long" to RANK_LONG,
        "java.math.BigInteger" to RANK_BIG_INTEGER,
        "java.math.BigDecimal" to RANK_BIG_DECIMAL,
        "java.lang.Float" to RANK_FLOAT,
        "java.lang.Double" to RANK_DOUBLE,
    )

    private val PRIMITIVE_FOR_RANK = mapOf(
        RANK_BYTE to SemanticType.Primitive(PrimitiveKind.BYTE),
        RANK_CHAR to SemanticType.Primitive(PrimitiveKind.CHAR),
        RANK_SHORT to SemanticType.Primitive(PrimitiveKind.SHORT),
        RANK_INT to SemanticType.Primitive(PrimitiveKind.INT),
        RANK_LONG to SemanticType.Primitive(PrimitiveKind.LONG),
        RANK_FLOAT to SemanticType.Primitive(PrimitiveKind.FLOAT),
        RANK_DOUBLE to SemanticType.Primitive(PrimitiveKind.DOUBLE),
    )

    private val KNOWN_FOR_RANK = mapOf(
        RANK_BYTE to SemanticType.Known("java.lang.Byte"),
        RANK_CHAR to SemanticType.Known("java.lang.Character"),
        RANK_SHORT to SemanticType.Known("java.lang.Short"),
        RANK_INT to SemanticType.Known("java.lang.Integer"),
        RANK_LONG to SemanticType.Known("java.lang.Long"),
        RANK_FLOAT to SemanticType.Known("java.lang.Float"),
        RANK_DOUBLE to SemanticType.Known("java.lang.Double"),
    )

    private fun getNumericRank(type: SemanticType): Int? = when (type) {
        is SemanticType.Primitive -> PRIMITIVE_RANKS[type.kind]
        is SemanticType.Known -> WRAPPER_RANKS[type.fqn]
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
