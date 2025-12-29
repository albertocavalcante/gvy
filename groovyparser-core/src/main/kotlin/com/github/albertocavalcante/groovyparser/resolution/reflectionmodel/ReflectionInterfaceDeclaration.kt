package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedInterfaceDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * A resolved interface declaration backed by Java reflection.
 */
class ReflectionInterfaceDeclaration(private val clazz: Class<*>, private val typeSolver: TypeSolver) :
    ResolvedInterfaceDeclaration {

    init {
        require(clazz.isInterface) { "${clazz.name} is not an interface" }
    }

    override val name: String = clazz.simpleName

    override val qualifiedName: String = clazz.canonicalName ?: clazz.name

    override val extendedInterfaces: List<ResolvedReferenceType>
        get() = clazz.interfaces.map { createReferenceType(it) }

    override fun getAncestors(): List<ResolvedReferenceType> = extendedInterfaces

    override fun getDeclaredFields(): List<ResolvedFieldDeclaration> = clazz.declaredFields.map {
        ReflectionFieldDeclaration(it, typeSolver)
    }

    override fun getDeclaredMethods(): List<ResolvedMethodDeclaration> = clazz.declaredMethods.map {
        ReflectionMethodDeclaration(it, typeSolver)
    }

    override fun getDeclaredConstructors(): List<ResolvedConstructorDeclaration> {
        return emptyList() // Interfaces don't have constructors
    }

    override fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> = clazz.typeParameters.map {
        ReflectionTypeParameterDeclaration(it, this, typeSolver)
    }

    private fun createReferenceType(clazz: Class<*>): ResolvedReferenceType {
        val ref = typeSolver.tryToSolveType(clazz.canonicalName ?: clazz.name)
        return if (ref.isSolved) {
            ResolvedReferenceType(ref.getDeclaration())
        } else {
            ResolvedReferenceType(ReflectionInterfaceDeclaration(clazz, typeSolver))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionInterfaceDeclaration) return false
        return clazz == other.clazz
    }

    override fun hashCode(): Int = clazz.hashCode()

    override fun toString(): String = "ReflectionInterfaceDeclaration[$qualifiedName]"
}
