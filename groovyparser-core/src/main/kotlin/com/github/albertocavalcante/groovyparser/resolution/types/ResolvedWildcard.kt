package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents a wildcard type.
 *
 * Wildcards are used in generic type arguments:
 * - `?` - unbounded wildcard
 * - `? extends T` - upper bounded wildcard
 * - `? super T` - lower bounded wildcard
 */
class ResolvedWildcard private constructor(val boundType: BoundType, val bound: ResolvedType?) : ResolvedType {

    /**
     * The type of bound for a wildcard.
     */
    enum class BoundType {
        /** No bound (?) */
        UNBOUNDED,

        /** Upper bound (? extends T) */
        EXTENDS,

        /** Lower bound (? super T) */
        SUPER,
    }

    override fun describe(): String = when (boundType) {
        BoundType.UNBOUNDED -> "?"
        BoundType.EXTENDS -> "? extends ${bound?.describe()}"
        BoundType.SUPER -> "? super ${bound?.describe()}"
    }

    override fun isWildcard(): Boolean = true

    override fun asWildcard(): ResolvedWildcard = this

    /**
     * Returns true if this is an unbounded wildcard (?).
     */
    fun isUnbounded(): Boolean = boundType == BoundType.UNBOUNDED

    /**
     * Returns true if this wildcard has an upper bound (? extends T).
     */
    fun isExtends(): Boolean = boundType == BoundType.EXTENDS

    /**
     * Returns true if this wildcard has a lower bound (? super T).
     */
    fun isSuper(): Boolean = boundType == BoundType.SUPER

    override fun isAssignableBy(other: ResolvedType): Boolean {
        return when (boundType) {
            BoundType.UNBOUNDED -> true
            BoundType.EXTENDS -> bound?.isAssignableBy(other) ?: true
            BoundType.SUPER -> {
                val superBound = bound ?: return true
                other.isAssignableBy(superBound)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedWildcard) return false
        return boundType == other.boundType && bound == other.bound
    }

    override fun hashCode(): Int = 31 * boundType.hashCode() + (bound?.hashCode() ?: 0)

    override fun toString(): String = describe()

    companion object {
        /**
         * Creates an unbounded wildcard (?).
         */
        fun unbounded(): ResolvedWildcard = ResolvedWildcard(BoundType.UNBOUNDED, null)

        /**
         * Creates an upper bounded wildcard (? extends T).
         */
        fun extendsBound(bound: ResolvedType): ResolvedWildcard = ResolvedWildcard(BoundType.EXTENDS, bound)

        /**
         * Creates a lower bounded wildcard (? super T).
         */
        fun superBound(bound: ResolvedType): ResolvedWildcard = ResolvedWildcard(BoundType.SUPER, bound)
    }
}
