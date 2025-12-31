package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import java.lang.reflect.Parameter

/**
 * A resolved parameter declaration backed by Java reflection.
 */
class ReflectionParameterDeclaration(
    private val parameter: Parameter,
    private val typeSolver: TypeSolver,
    private val isVarArg: Boolean = false,
) : ResolvedParameterDeclaration {

    override val name: String = parameter.name

    override val type: ResolvedType
        get() = ReflectionFactory.typeForClass(parameter.type, typeSolver)

    override fun isVariadic(): Boolean = isVarArg

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionParameterDeclaration) return false
        return parameter == other.parameter
    }

    override fun hashCode(): Int = parameter.hashCode()

    override fun toString(): String = "ReflectionParameterDeclaration[$name: ${type.describe()}]"
}
