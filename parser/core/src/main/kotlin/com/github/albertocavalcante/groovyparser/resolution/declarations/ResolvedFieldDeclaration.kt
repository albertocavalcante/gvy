package com.github.albertocavalcante.groovyparser.resolution.declarations

/**
 * Represents a resolved field declaration.
 */
interface ResolvedFieldDeclaration : ResolvedValueDeclaration {

    /**
     * The type that declares this field.
     */
    val declaringType: ResolvedTypeDeclaration

    /**
     * Returns true if this field is static.
     */
    fun isStatic(): Boolean

    /**
     * Returns true if this field is final.
     */
    fun isFinal(): Boolean

    /**
     * Returns true if this field is public.
     */
    fun isPublic(): Boolean

    /**
     * Returns true if this field is private.
     */
    fun isPrivate(): Boolean

    /**
     * Returns true if this field is protected.
     */
    fun isProtected(): Boolean
}
