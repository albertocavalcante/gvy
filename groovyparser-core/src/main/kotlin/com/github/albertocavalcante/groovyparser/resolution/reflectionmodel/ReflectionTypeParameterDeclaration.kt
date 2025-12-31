package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import java.lang.reflect.TypeVariable

/**
 * A resolved type parameter declaration backed by Java reflection.
 */
class ReflectionTypeParameterDeclaration(
    private val typeVariable: TypeVariable<*>,
    override val container: ResolvedDeclaration,
    private val typeSolver: TypeSolver,
) : ResolvedTypeParameterDeclaration {

    override val name: String = typeVariable.name

    override fun getBounds(): List<ResolvedType> = typeVariable.bounds.mapNotNull { bound ->
        when (bound) {
            is Class<*> -> ReflectionFactory.typeForClass(bound, typeSolver)
            else -> null // Handle parameterized types, etc. later
        }
    }.ifEmpty {
        // Default to Object if no bounds
        listOf(ReflectionFactory.typeForClass(Any::class.java, typeSolver))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionTypeParameterDeclaration) return false
        return typeVariable == other.typeVariable
    }

    override fun hashCode(): Int = typeVariable.hashCode()

    override fun toString(): String = "ReflectionTypeParameterDeclaration[$name]"
}
