package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Represents a resolved method declaration.
 */
interface ResolvedMethodDeclaration : ResolvedDeclaration {

    /**
     * The type that declares this method.
     */
    val declaringType: ResolvedTypeDeclaration

    /**
     * The return type of this method.
     */
    val returnType: ResolvedType

    /**
     * The number of parameters.
     */
    fun getNumberOfParams(): Int

    /**
     * Returns the parameter at the given index.
     */
    fun getParam(index: Int): ResolvedParameterDeclaration

    /**
     * Returns all parameters.
     */
    fun getParameters(): List<ResolvedParameterDeclaration>

    /**
     * Returns true if this method is static.
     */
    fun isStatic(): Boolean

    /**
     * Returns true if this method is abstract.
     */
    fun isAbstract(): Boolean

    /**
     * Returns true if this method is final.
     */
    fun isFinal(): Boolean

    /**
     * Returns true if this method is public.
     */
    fun isPublic(): Boolean

    /**
     * Returns true if this method is private.
     */
    fun isPrivate(): Boolean

    /**
     * Returns true if this method is protected.
     */
    fun isProtected(): Boolean

    /**
     * Returns the type parameters of this method.
     */
    fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> = emptyList()

    /**
     * Returns a signature string for this method.
     */
    fun getSignature(): String = buildString {
        append(name)
        append("(")
        append(getParameters().joinToString(", ") { it.type.describe() })
        append(")")
    }
}
