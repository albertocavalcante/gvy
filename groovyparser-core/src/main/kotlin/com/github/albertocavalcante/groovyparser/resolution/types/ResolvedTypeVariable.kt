package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents a type variable (generic type parameter).
 *
 * For example, in `class Box<T>`, `T` is a type variable.
 *
 * @param name The name of the type variable (e.g., "T", "E")
 * @param bounds The upper bounds of the type variable (defaults to Object)
 */
class ResolvedTypeVariable(val name: String, val bounds: List<ResolvedType> = emptyList()) : ResolvedType {

    override fun describe(): String = name

    override fun isTypeVariable(): Boolean = true

    override fun asTypeVariable(): ResolvedTypeVariable = this

    override fun isAssignableBy(other: ResolvedType): Boolean = when {
        other.isTypeVariable() -> other.asTypeVariable().name == name
        bounds.isEmpty() -> true // No bounds means Object bound, accepts anything
        else -> bounds.any { it.isAssignableBy(other) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedTypeVariable) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = describe()
}
