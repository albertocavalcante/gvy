package com.github.albertocavalcante.groovyparser.resolution.types

import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration

/**
 * Represents a reference type (class, interface, enum).
 *
 * @param declaration The type declaration that defines this type
 * @param typeArguments The type arguments if this is a parameterized type
 */
class ResolvedReferenceType(
    val declaration: ResolvedTypeDeclaration,
    val typeArguments: List<ResolvedType> = emptyList(),
) : ResolvedType {

    override fun describe(): String = buildString {
        append(declaration.qualifiedName)
        if (typeArguments.isNotEmpty()) {
            append("<")
            append(typeArguments.joinToString(", ") { it.describe() })
            append(">")
        }
    }

    override fun isReferenceType(): Boolean = true

    override fun asReferenceType(): ResolvedReferenceType = this

    override fun isAssignableBy(other: ResolvedType): Boolean = when {
        other.isNull() -> true // null is assignable to any reference type
        other.isReferenceType() -> isAssignableFromReference(other.asReferenceType())
        other.isPrimitive() -> isAssignableFromBoxed(other.asPrimitive())
        else -> false
    }

    /**
     * Checks if this reference type is assignable from another reference type.
     */
    private fun isAssignableFromReference(other: ResolvedReferenceType): Boolean {
        // Same type
        if (declaration.qualifiedName == other.declaration.qualifiedName) {
            return true
        }

        // Check if this is a supertype of other
        // java.lang.Object is assignable from everything
        if (declaration.qualifiedName == "java.lang.Object") {
            return true
        }

        // Check ancestors
        return other.declaration.getAncestors().any { ancestor ->
            ancestor.declaration.qualifiedName == this.declaration.qualifiedName
        }
    }

    /**
     * Checks if this reference type is assignable from a boxed primitive.
     */
    private fun isAssignableFromBoxed(primitive: ResolvedPrimitiveType): Boolean {
        val boxedName = primitive.box()
        return declaration.qualifiedName == boxedName ||
            declaration.qualifiedName == "java.lang.Object" ||
            declaration.qualifiedName == "java.lang.Number" && primitive in ResolvedPrimitiveType.NUMERIC_TYPES
    }

    /**
     * Returns true if this is a parameterized type.
     */
    fun isParameterized(): Boolean = typeArguments.isNotEmpty()

    /**
     * Returns the raw type (without type arguments).
     */
    fun toRawType(): ResolvedReferenceType = if (typeArguments.isEmpty()) this else ResolvedReferenceType(declaration)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedReferenceType) return false
        return declaration.qualifiedName == other.declaration.qualifiedName &&
            typeArguments == other.typeArguments
    }

    override fun hashCode(): Int = 31 * declaration.qualifiedName.hashCode() + typeArguments.hashCode()

    override fun toString(): String = describe()
}
