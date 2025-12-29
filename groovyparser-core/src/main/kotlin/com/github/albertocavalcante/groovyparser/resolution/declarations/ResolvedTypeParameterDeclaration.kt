package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Represents a resolved type parameter declaration (generic type parameter).
 *
 * For example, in `class Box<T extends Comparable<T>>`, `T extends Comparable<T>` is a type parameter.
 */
interface ResolvedTypeParameterDeclaration : ResolvedDeclaration {

    /**
     * The container that declares this type parameter (class, interface, or method).
     */
    val container: ResolvedDeclaration

    /**
     * The upper bounds of this type parameter.
     * If no explicit bounds, this contains Object.
     */
    fun getBounds(): List<ResolvedType>

    /**
     * Returns true if this type parameter has explicit bounds.
     */
    fun hasExplicitBounds(): Boolean = getBounds().any {
        it.isReferenceType() && it.asReferenceType().declaration.qualifiedName != "java.lang.Object"
    }
}
