package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * Represents a resolved class declaration.
 */
interface ResolvedClassDeclaration : ResolvedTypeDeclaration {

    override fun isClass(): Boolean = true

    override fun asClass(): ResolvedClassDeclaration = this

    /**
     * The superclass of this class, or null if this is java.lang.Object.
     */
    val superClass: ResolvedReferenceType?

    /**
     * The interfaces directly implemented by this class.
     */
    val interfaces: List<ResolvedReferenceType>

    /**
     * Returns true if this class is abstract.
     */
    fun isAbstract(): Boolean

    /**
     * Returns true if this class is final.
     */
    fun isFinal(): Boolean

    /**
     * Checks if this class is a subtype of the given type.
     */
    fun isAssignableBy(other: ResolvedTypeDeclaration): Boolean {
        if (qualifiedName == other.qualifiedName) return true

        // Check if other is a subclass
        if (other is ResolvedClassDeclaration) {
            val otherSuperClass = other.superClass
            if (otherSuperClass != null &&
                isAssignableBy(otherSuperClass.declaration)
            ) {
                return true
            }
        }

        // Check implemented interfaces
        if (other is ResolvedClassDeclaration) {
            for (iface in other.interfaces) {
                if (qualifiedName == iface.declaration.qualifiedName) {
                    return true
                }
            }
        }

        return false
    }
}
