package com.github.albertocavalcante.groovyparser.resolution.groovymodel

import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration

/**
 * A resolved constructor declaration backed by a parsed Groovy AST.
 */
class GroovyParserConstructorDeclaration(
    private val constructorDecl: ConstructorDeclaration,
    override val declaringType: ResolvedTypeDeclaration,
    private val typeSolver: TypeSolver,
) : ResolvedConstructorDeclaration {

    override val name: String = declaringType.name

    override fun getNumberOfParams(): Int = constructorDecl.parameters.size

    override fun getParam(index: Int): ResolvedParameterDeclaration =
        GroovyParserParameterDeclaration(constructorDecl.parameters[index], typeSolver)

    override fun getParameters(): List<ResolvedParameterDeclaration> = constructorDecl.parameters.map {
        GroovyParserParameterDeclaration(it, typeSolver)
    }

    // Constructors in Groovy default to public
    override fun isPublic(): Boolean = true

    override fun isPrivate(): Boolean = false

    override fun isProtected(): Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovyParserConstructorDeclaration) return false
        return declaringType.qualifiedName == other.declaringType.qualifiedName &&
            getSignature() == other.getSignature()
    }

    override fun hashCode(): Int = getSignature().hashCode()

    override fun toString(): String = "GroovyParserConstructorDeclaration[${getSignature()}]"
}
