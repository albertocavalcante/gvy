package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * Represents a resolved type declaration (class, interface, enum, annotation).
 */
interface ResolvedTypeDeclaration : ResolvedDeclaration {

    /**
     * The fully qualified name of this type (e.g., "java.util.List").
     */
    val qualifiedName: String

    /**
     * The package name of this type (e.g., "java.util").
     */
    val packageName: String
        get() = qualifiedName.substringBeforeLast('.', "")

    /**
     * Returns true if this is a class.
     */
    fun isClass(): Boolean = false

    /**
     * Returns true if this is an interface.
     */
    fun isInterface(): Boolean = false

    /**
     * Returns true if this is an enum.
     */
    fun isEnum(): Boolean = false

    /**
     * Returns true if this is an annotation type.
     */
    fun isAnnotation(): Boolean = false

    /**
     * Returns the list of direct ancestors (superclass and interfaces).
     */
    fun getAncestors(): List<ResolvedReferenceType>

    /**
     * Returns the fields declared in this type.
     */
    fun getDeclaredFields(): List<ResolvedFieldDeclaration>

    /**
     * Returns the methods declared in this type.
     */
    fun getDeclaredMethods(): List<ResolvedMethodDeclaration>

    /**
     * Returns the constructors declared in this type.
     */
    fun getDeclaredConstructors(): List<ResolvedConstructorDeclaration>

    /**
     * Returns the type parameters of this type (for generics).
     */
    fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> = emptyList()

    /**
     * Casts this to [ResolvedClassDeclaration].
     */
    fun asClass(): ResolvedClassDeclaration = throw IllegalStateException("$qualifiedName is not a class")

    /**
     * Casts this to [ResolvedInterfaceDeclaration].
     */
    fun asInterface(): ResolvedInterfaceDeclaration = throw IllegalStateException("$qualifiedName is not an interface")

    /**
     * Casts this to [ResolvedEnumDeclaration].
     */
    fun asEnum(): ResolvedEnumDeclaration = throw IllegalStateException("$qualifiedName is not an enum")
}
