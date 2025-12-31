package com.github.albertocavalcante.groovyparser.resolution.groovymodel

import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * A resolved method declaration backed by a parsed Groovy AST.
 */
class GroovyParserMethodDeclaration(
    private val methodDecl: MethodDeclaration,
    override val declaringType: ResolvedTypeDeclaration,
    private val typeSolver: TypeSolver,
) : ResolvedMethodDeclaration {

    override val name: String = methodDecl.name

    override val returnType: ResolvedType
        get() = GroovyParserTypeResolver.resolveType(methodDecl.returnType, typeSolver)

    override fun getNumberOfParams(): Int = methodDecl.parameters.size

    override fun getParam(index: Int): ResolvedParameterDeclaration =
        GroovyParserParameterDeclaration(methodDecl.parameters[index], typeSolver)

    override fun getParameters(): List<ResolvedParameterDeclaration> = methodDecl.parameters.map {
        GroovyParserParameterDeclaration(it, typeSolver)
    }

    override fun isStatic(): Boolean = methodDecl.isStatic

    override fun isAbstract(): Boolean = methodDecl.isAbstract

    override fun isFinal(): Boolean = methodDecl.isFinal

    // Groovy methods default to public
    override fun isPublic(): Boolean = true

    override fun isPrivate(): Boolean = false

    override fun isProtected(): Boolean = false

    override fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> {
        // Type parameters from Groovy AST are not yet supported
        return emptyList()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovyParserMethodDeclaration) return false
        return name == other.name &&
            declaringType.qualifiedName == other.declaringType.qualifiedName &&
            getSignature() == other.getSignature()
    }

    override fun hashCode(): Int = 31 * name.hashCode() + getSignature().hashCode()

    override fun toString(): String = "GroovyParserMethodDeclaration[${getSignature()}]"
}
