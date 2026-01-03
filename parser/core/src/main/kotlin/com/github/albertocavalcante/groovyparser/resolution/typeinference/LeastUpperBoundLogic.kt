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
    private const val JAVA_MATH_BIG_INTEGER = "java.math.BigInteger"
    private const val JAVA_MATH_BIG_DECIMAL = "java.math.BigDecimal"
    private const val GROOVY_LANG_GSTRING = "groovy.lang.GString"
    private const val JAVA_LANG_CHARSEQUENCE = "java.lang.CharSequence"
    private const val JAVA_LANG_COMPARABLE = "java.lang.Comparable"

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

    // Interface priority ranks (for common ancestor selection)
    private const val PRIORITY_COMMON_COLLECTIONS = 1
    private const val PRIORITY_COLLECTION = 2
    private const val PRIORITY_ITERABLE = 3
    private const val PRIORITY_CHARSEQUENCE = 4
    private const val PRIORITY_COMPARABLE = 5
    private const val PRIORITY_SERIALIZABLE = 10
    private const val PRIORITY_OBJECT = 100

    // Numeric precedence for promoteNumericTypes (different ranking system)
    private const val PRECEDENCE_BOOLEAN = 0
    private const val PRECEDENCE_BYTE = 1
    private const val PRECEDENCE_CHAR = 2
    private const val PRECEDENCE_SHORT = 2 // Same as char in promotion hierarchy
    private const val PRECEDENCE_INT = 3
    private const val PRECEDENCE_LONG = 4
    private const val PRECEDENCE_FLOAT = 5
    private const val PRECEDENCE_DOUBLE = 6

    // Numeric type precedence including BigInteger/BigDecimal
    // Higher number = wider type (wins in LUB)
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

    // Preferred types when multiple common ancestors exist
    // Lower number = more preferred
    private val INTERFACE_PRIORITY = mapOf(
        "java.util.List" to PRIORITY_COMMON_COLLECTIONS,
        "java.util.Set" to PRIORITY_COMMON_COLLECTIONS,
        "java.util.Map" to PRIORITY_COMMON_COLLECTIONS,
        "java.lang.Number" to PRIORITY_COMMON_COLLECTIONS,
        "java.util.Collection" to PRIORITY_COLLECTION,
        "java.lang.Iterable" to PRIORITY_ITERABLE,
        JAVA_LANG_CHARSEQUENCE to PRIORITY_CHARSEQUENCE,
        JAVA_LANG_COMPARABLE to PRIORITY_COMPARABLE,
        "java.io.Serializable" to PRIORITY_SERIALIZABLE,
        JAVA_LANG_OBJECT to PRIORITY_OBJECT,
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

        // Check for Generic LUB (List, Map, Closure)
        val genericLub = checkGenericLub(nonNullTypes, typeSolver)
        if (genericLub != null) return genericLub

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

    private const val JAVA_UTIL_LIST = "java.util.List"
    private const val JAVA_UTIL_MAP = "java.util.Map"
    private const val GROOVY_LANG_CLOSURE = "groovy.lang.Closure"

    /**
     * Checks for generic types like List, Map, Closure and computes LUB of type arguments.
     */
    private fun checkGenericLub(types: List<ResolvedType>, typeSolver: TypeSolver): ResolvedType? {
        // Must all be reference types
        if (!types.all { it.isReferenceType() }) return null
        val refTypes = types.map { it.asReferenceType() }

        // Candidate generic interfaces to check
        val candidates = listOf(JAVA_UTIL_LIST, JAVA_UTIL_MAP, GROOVY_LANG_CLOSURE)

        for (targetName in candidates) {
            // Check if all types are assignable to this target
            // We use a simplified check: do they implement/extend it?
            // And do they provide type arguments?

            // To properly resolve generics, we'd need full generic type hierarchy logic.
            // For now, we use a heuristic:
            // 1. If the type IS the target, use its args.
            // 2. If the type is widely known implementation (ArrayList -> List), we assume args map 1:1.

            val typeArgsList = refTypes.map { extractTypeArgsForTarget(it, targetName) }

            if (typeArgsList.any { it == null }) continue // This target doesn't fit some input

            val validArgs = typeArgsList.filterNotNull()
            if (validArgs.isEmpty()) continue

            // Verify all have same number of args
            val numArgs = validArgs.first().size
            if (validArgs.any { it.size != numArgs }) continue

            // Compute LUB for each argument position
            val lubArgs = mutableListOf<ResolvedType>()
            for (i in 0 until numArgs) {
                val argsAtPos = validArgs.map { it[i] }
                lubArgs.add(lub(argsAtPos, typeSolver))
            }

            val targetDecl = typeSolver.tryToSolveType(targetName)
            if (targetDecl.isSolved) {
                return ResolvedReferenceType(targetDecl.getDeclaration(), lubArgs)
            }
        }

        return null
    }

    private fun extractTypeArgsForTarget(type: ResolvedReferenceType, targetName: String): List<ResolvedType>? {
        // Case 1: The type IS the target
        if (type.declaration.qualifiedName == targetName) {
            return type.typeArguments
        }

        // Case 2: Direct implementation with matching generic count (Heuristic)
        // e.g. ArrayList<T> implements List<T>
        // We check if type is subtype of target
        if (type.declaration.qualifiedName == targetName) {
            return type.typeArguments
        }

        // BFS traversal to find the target ancestor
        val queue = ArrayDeque<ResolvedReferenceType>()
        queue.addAll(type.declaration.getAncestors())

        val visited = mutableSetOf<String>()
        visited.add(type.declaration.qualifiedName)

        while (queue.isNotEmpty()) {
            val ancestor = queue.removeFirst()
            val qName = ancestor.declaration.qualifiedName
            if (!visited.add(qName)) continue

            if (qName == targetName) {
                // Found target!
                return ancestor.typeArguments
            }

            // Add ancestors of this ancestor
            queue.addAll(ancestor.declaration.getAncestors())
        }

        // Fallback [HEURISTIC NOTE]
        // If we can't find the target in ancestors (e.g. broken hierarchy or incomplete TypeSolver),
        // we utilize a "Last Resort" heuristic:
        //
        // Logic:
        //   If the type strictly *is* a subtype of the target (verified via deep check),
        //   AND it has type arguments, we assume those arguments map to the target's parameters.
        //
        // Justification for Fallback:
        //   - Robustness: Preserves some inference capability even when TypeSolver fails to resolve intermediate ancestors.
        //   - Legacy Support: Handles older code or partial ASTs where full ancestor chains aren't resolvable.
        //
        // Risk:
        //   - Incorrect Mapping: If the subtype's arguments don't align 1:1 with the target (e.g. reordered parameters),
        //     this will produce wrong types. However, returning *something* is often better than 'Object' in an IDE context.
        if (isSubtypeOf(type, targetName) && type.typeArguments.isNotEmpty()) {
            return type.typeArguments
        }

        // If we still haven't found it, or the heuristic didn't apply, return null.
        return null
    }

    private fun isSubtypeOf(type: ResolvedReferenceType, targetName: String): Boolean {
        if (type.declaration.qualifiedName == targetName) return true
        // Use BFS for subtype check too, or reuse the logic above?
        // Simple recursive check:
        // Note: this implementation of isSubtypeOf was shallow before!
        // Now we fix it to be deep.
        val queue = ArrayDeque<ResolvedReferenceType>()
        queue.addAll(type.declaration.getAncestors())
        val visited = mutableSetOf<String>()
        visited.add(type.declaration.qualifiedName)

        while (queue.isNotEmpty()) {
            val ancestor = queue.removeFirst()
            val qName = ancestor.declaration.qualifiedName
            if (qName == targetName) return true
            if (!visited.add(qName)) continue
            queue.addAll(ancestor.declaration.getAncestors())
        }
        return false
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
                        ResolvedPrimitiveType.BYTE -> RANK_BYTE
                        ResolvedPrimitiveType.CHAR -> RANK_CHAR
                        ResolvedPrimitiveType.SHORT -> RANK_SHORT
                        ResolvedPrimitiveType.INT -> RANK_INT
                        ResolvedPrimitiveType.LONG -> RANK_LONG
                        ResolvedPrimitiveType.FLOAT -> RANK_FLOAT
                        ResolvedPrimitiveType.DOUBLE -> RANK_DOUBLE
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
        require(primitives.none { it == ResolvedPrimitiveType.BOOLEAN }) { "Cannot compute LUB involving boolean" }

        // Find the widest type
        return primitives.maxByOrNull { getNumericPrecedence(it) }
            ?: ResolvedPrimitiveType.INT
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

    /**
     * Finds the common ancestor of reference types.
     *
     * [HEURISTIC NOTE]
     * Java/Groovy support multiple inheritance of interfaces, meaning there is often no single "Least Upper Bound".
     * A set of types {ArrayList, LinkedList} has common ancestors {List, Collection, Iterable, Serializable, Cloneable, Object}.
     *
     * We must select ONE to represent the type in the IDE.
     *
     * Heuristic Strategy:
     * 1. Compute intersection of all ancestor sets.
     * 2. Filter out 'Object'.
     * 3. Select the "Best" based on `INTERFACE_PRIORITY`:
     *    - Prefer semantically rich types (List, Map) over structural ones (Serializable, Cloneable).
     *    - Priority map values: List=1, Collection=2, Iterable=3, Serializable=20.
     *
     * Trade-offs:
     * - We might hide capabilities (e.g. failing to show 'Cloneable' methods if 'List' is picked).
     * - This matches developer intuition (List + List = List, not Serializable).
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
