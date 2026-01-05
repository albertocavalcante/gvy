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
}

/**
 * Returns true if this is a primitive type.
 */
fun ResolvedType.isPrimitive(): Boolean = this is ResolvedPrimitiveType

/**
 * Returns true if this is a reference type (class, interface, enum).
 */
fun ResolvedType.isReferenceType(): Boolean = this is ResolvedReferenceType

/**
 * Returns true if this is an array type.
 */
fun ResolvedType.isArray(): Boolean = this is ResolvedArrayType

/**
 * Returns true if this is the void type.
 */
fun ResolvedType.isVoid(): Boolean = this is ResolvedVoidType

/**
 * Returns true if this is a type variable.
 */
fun ResolvedType.isTypeVariable(): Boolean = this is ResolvedTypeVariable

/**
 * Returns true if this is a wildcard type.
 */
fun ResolvedType.isWildcard(): Boolean = this is ResolvedWildcard

/**
 * Returns true if this is the null type.
 */
fun ResolvedType.isNull(): Boolean = this is ResolvedNullType

/**
 * Returns the array nesting level (0 for non-arrays).
 */
fun ResolvedType.arrayLevel(): Int = if (this is ResolvedArrayType) 1 + this.componentType.arrayLevel() else 0

// Downcasting methods

/**
 * Casts this type to [ResolvedPrimitiveType].
 * @throws IllegalStateException if this is not a primitive type
 */
fun ResolvedType.asPrimitive(): ResolvedPrimitiveType =
    this as? ResolvedPrimitiveType ?: error("${describe()} is not a primitive type")

/**
 * Casts this type to [ResolvedReferenceType].
 * @throws IllegalStateException if this is not a reference type
 */
fun ResolvedType.asReferenceType(): ResolvedReferenceType =
    this as? ResolvedReferenceType ?: error("${describe()} is not a reference type")

/**
 * Casts this type to [ResolvedArrayType].
 * @throws IllegalStateException if this is not an array type
 */
fun ResolvedType.asArrayType(): ResolvedArrayType =
    this as? ResolvedArrayType ?: error("${describe()} is not an array type")

/**
 * Casts this type to [ResolvedTypeVariable].
 * @throws IllegalStateException if this is not a type variable
 */
fun ResolvedType.asTypeVariable(): ResolvedTypeVariable =
    this as? ResolvedTypeVariable ?: error("${describe()} is not a type variable")

/**
 * Casts this type to [ResolvedWildcard].
 * @throws IllegalStateException if this is not a wildcard
 */
fun ResolvedType.asWildcard(): ResolvedWildcard = this as? ResolvedWildcard ?: error("${describe()} is not a wildcard")
