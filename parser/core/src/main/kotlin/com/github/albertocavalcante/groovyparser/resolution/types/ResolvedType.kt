package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents a resolved type in the Groovy type system.
 *
 * This is the base interface for all resolved types, including:
 * - Primitive types (int, boolean, etc.)
 * - Reference types (classes, interfaces)
 * - Array types
 * - Void type
 * - Type variables (generics)
 * - Wildcards
 */
sealed interface ResolvedType {

    /**
     * Returns a human-readable description of this type.
     */
    fun describe(): String

    /**
     * Checks if this type can be assigned a value of the given type.
     *
     * @param other The type to check assignment from
     * @return true if a value of type [other] can be assigned to this type
     */
    fun isAssignableBy(other: ResolvedType): Boolean

    /**
     * Returns true if this is a primitive type.
     */
    fun isPrimitive(): Boolean = false

    /**
     * Returns true if this is a reference type (class, interface, enum).
     */
    fun isReferenceType(): Boolean = false

    /**
     * Returns true if this is an array type.
     */
    fun isArray(): Boolean = false

    /**
     * Returns true if this is the void type.
     */
    fun isVoid(): Boolean = false

    /**
     * Returns true if this is a type variable.
     */
    fun isTypeVariable(): Boolean = false

    /**
     * Returns true if this is a wildcard type.
     */
    fun isWildcard(): Boolean = false

    /**
     * Returns true if this is the null type.
     */
    fun isNull(): Boolean = false

    /**
     * Returns the array nesting level (0 for non-arrays).
     */
    fun arrayLevel(): Int = 0

    // Downcasting methods

    /**
     * Casts this type to [ResolvedPrimitiveType].
     * @throws IllegalStateException if this is not a primitive type
     */
    fun asPrimitive(): ResolvedPrimitiveType = error("${describe()} is not a primitive type")

    /**
     * Casts this type to [ResolvedReferenceType].
     * @throws IllegalStateException if this is not a reference type
     */
    fun asReferenceType(): ResolvedReferenceType = error("${describe()} is not a reference type")

    /**
     * Casts this type to [ResolvedArrayType].
     * @throws IllegalStateException if this is not an array type
     */
    fun asArrayType(): ResolvedArrayType = error("${describe()} is not an array type")

    /**
     * Casts this type to [ResolvedTypeVariable].
     * @throws IllegalStateException if this is not a type variable
     */
    fun asTypeVariable(): ResolvedTypeVariable = error("${describe()} is not a type variable")

    /**
     * Casts this type to [ResolvedWildcard].
     * @throws IllegalStateException if this is not a wildcard
     */
    fun asWildcard(): ResolvedWildcard = error("${describe()} is not a wildcard")
}
