package com.github.albertocavalcante.groovyparser.resolution.declarations

/**
 * Represents a resolved constructor declaration.
 */
interface ResolvedConstructorDeclaration : ResolvedDeclaration {

    /**
     * The type that declares this constructor.
     */
    val declaringType: ResolvedTypeDeclaration

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
     * Returns true if this constructor is public.
     */
    fun isPublic(): Boolean

    /**
     * Returns true if this constructor is private.
     */
    fun isPrivate(): Boolean

    /**
     * Returns true if this constructor is protected.
     */
    fun isProtected(): Boolean

    /**
     * Returns a signature string for this constructor.
     */
    fun getSignature(): String = buildString {
        append(declaringType.name)
        append("(")
        append(getParameters().joinToString(", ") { it.type.describe() })
        append(")")
    }
}
