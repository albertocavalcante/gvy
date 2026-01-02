package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedEnumConstantDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedEnumDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * A resolved enum declaration backed by Java reflection.
 */
class ReflectionEnumDeclaration(private val clazz: Class<*>, private val typeSolver: TypeSolver) :
    ResolvedEnumDeclaration {

    init {
        require(clazz.isEnum) { "${clazz.name} is not an enum" }
    }

    override val name: String = clazz.simpleName

    override val qualifiedName: String = clazz.canonicalName ?: clazz.name

    override fun getEnumConstants(): List<ResolvedEnumConstantDeclaration> = clazz.enumConstants?.map { constant ->
        ReflectionEnumConstantDeclaration(constant as Enum<*>, this, typeSolver)
    } ?: emptyList()

    override fun getAncestors(): List<ResolvedReferenceType> {
        val ancestors = mutableListOf<ResolvedReferenceType>()
        // Enums implicitly extend java.lang.Enum
        typeSolver.tryToSolveType("java.lang.Enum").let {
            if (it.isSolved) ancestors.add(ResolvedReferenceType(it.getDeclaration()))
        }
        // Add implemented interfaces
        clazz.interfaces.forEach { iface ->
            val ref = typeSolver.tryToSolveType(iface.canonicalName ?: iface.name)
            if (ref.isSolved) ancestors.add(ResolvedReferenceType(ref.getDeclaration()))
        }
        return ancestors
    }

    override fun getDeclaredFields(): List<ResolvedFieldDeclaration> = clazz.declaredFields
        .filterNot { it.isEnumConstant }
        .map { ReflectionFieldDeclaration(it, typeSolver) }

    override fun getDeclaredMethods(): List<ResolvedMethodDeclaration> = clazz.declaredMethods.map {
        ReflectionMethodDeclaration(it, typeSolver)
    }

    override fun getDeclaredConstructors(): List<ResolvedConstructorDeclaration> = clazz.declaredConstructors.map {
        ReflectionConstructorDeclaration(it, typeSolver)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionEnumDeclaration) return false
        return clazz == other.clazz
    }

    override fun hashCode(): Int = clazz.hashCode()

    override fun toString(): String = "ReflectionEnumDeclaration[$qualifiedName]"
}
