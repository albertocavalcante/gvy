package com.github.albertocavalcante.groovyparser.resolution.groovymodel

import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * A resolved parameter declaration backed by a parsed Groovy AST.
 */
class GroovyParserParameterDeclaration(private val param: Parameter, private val typeSolver: TypeSolver) :
    ResolvedParameterDeclaration {

    override val name: String = param.name

    override val type: ResolvedType
        get() = GroovyParserTypeResolver.resolveType(param.type, typeSolver)

    override fun isVariadic(): Boolean = false // Not supported in AST yet

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovyParserParameterDeclaration) return false
        return name == other.name && type == other.type
    }

    override fun hashCode(): Int = 31 * name.hashCode() + type.hashCode()

    override fun toString(): String = "GroovyParserParameterDeclaration[$name: ${type.describe()}]"
}
