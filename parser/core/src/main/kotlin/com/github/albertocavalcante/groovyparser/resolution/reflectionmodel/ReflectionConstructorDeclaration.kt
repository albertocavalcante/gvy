package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * A resolved constructor declaration backed by Java reflection.
 */
class ReflectionConstructorDeclaration(private val constructor: Constructor<*>, private val typeSolver: TypeSolver) :
    ResolvedConstructorDeclaration {

    override val name: String = constructor.declaringClass.simpleName

    override val declaringType: ResolvedTypeDeclaration
        get() {
            val ref = typeSolver.tryToSolveType(
                constructor.declaringClass.canonicalName ?: constructor.declaringClass.name,
            )
            return if (ref.isSolved) {
                ref.getDeclaration()
            } else {
                ReflectionClassDeclaration(
                    constructor.declaringClass,
                    typeSolver,
                )
            }
        }

    override fun getNumberOfParams(): Int = constructor.parameterCount

    override fun getParam(index: Int): ResolvedParameterDeclaration = ReflectionParameterDeclaration(
        constructor.parameters[index],
        typeSolver,
        index == constructor.parameterCount - 1 && constructor.isVarArgs,
    )

    override fun getParameters(): List<ResolvedParameterDeclaration> = constructor.parameters.mapIndexed {
            index,
            param,
        ->
        ReflectionParameterDeclaration(
            param,
            typeSolver,
            index == constructor.parameterCount - 1 && constructor.isVarArgs,
        )
    }

    override fun isPublic(): Boolean = Modifier.isPublic(constructor.modifiers)

    override fun isPrivate(): Boolean = Modifier.isPrivate(constructor.modifiers)

    override fun isProtected(): Boolean = Modifier.isProtected(constructor.modifiers)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionConstructorDeclaration) return false
        return constructor == other.constructor
    }

    override fun hashCode(): Int = constructor.hashCode()

    override fun toString(): String = "ReflectionConstructorDeclaration[${getSignature()}]"
}
