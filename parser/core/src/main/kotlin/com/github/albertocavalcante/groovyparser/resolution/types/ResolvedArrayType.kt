package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents an array type.
 *
 * @param componentType The type of elements in the array
 */
class ResolvedArrayType(val componentType: ResolvedType) : ResolvedType {

    override fun describe(): String = "${componentType.describe()}[]"

    override fun isArray(): Boolean = true

    override fun asArrayType(): ResolvedArrayType = this

    override fun arrayLevel(): Int = 1 + componentType.arrayLevel()

    override fun isAssignableBy(other: ResolvedType): Boolean = when {
        !other.isArray() -> false
        else -> {
            val otherArray = other.asArrayType()
            // Arrays are covariant for reference types in Java/Groovy
            // But for primitive arrays, component types must match exactly
            if (componentType.isPrimitive() || otherArray.componentType.isPrimitive()) {
                componentType == otherArray.componentType
            } else {
                componentType.isAssignableBy(otherArray.componentType)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedArrayType) return false
        return componentType == other.componentType
    }

    override fun hashCode(): Int = componentType.hashCode() * 31

    override fun toString(): String = describe()
}
