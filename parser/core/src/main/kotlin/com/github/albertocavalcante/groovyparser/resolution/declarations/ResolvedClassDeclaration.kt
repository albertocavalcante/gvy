// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
     * Checks if this class is a subtype of the given type: [other] itself, one of its superclasses, or an interface
     * one of them implements directly. The superclass chain is walked once per class name, so a cycle such as
     * `class A extends B {}; class B extends A {}` ends the walk instead of recursing forever (#1411).
     */
    fun isAssignableBy(other: ResolvedTypeDeclaration): Boolean {
        if (qualifiedName == other.qualifiedName) return true
        val seen = mutableSetOf<String>()
        var current: ResolvedTypeDeclaration? = other
        while (current is ResolvedClassDeclaration && seen.add(current.qualifiedName)) {
            val type = current
            val superDeclaration = type.superClass?.declaration
            if (superDeclaration?.qualifiedName == qualifiedName ||
                type.interfaces.any { it.declaration.qualifiedName == qualifiedName }
            ) {
                return true
            }
            current = superDeclaration
        }
        return false
    }
}
