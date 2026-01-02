package com.github.albertocavalcante.groovyparser.resolution.groovymodel

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * A resolved field declaration backed by a parsed Groovy AST.
 */
class GroovyParserFieldDeclaration(
    private val fieldDecl: FieldDeclaration,
    override val declaringType: ResolvedTypeDeclaration,
    private val typeSolver: TypeSolver,
) : ResolvedFieldDeclaration {

    override val name: String = fieldDecl.name

    override val type: ResolvedType
        get() = GroovyParserTypeResolver.resolveType(fieldDecl.type, typeSolver)

    override val declarationNode: Node
        get() = fieldDecl

    override fun isStatic(): Boolean = fieldDecl.isStatic

    override fun isFinal(): Boolean = fieldDecl.isFinal

    // Groovy fields default to private with generated accessors
    override fun isPublic(): Boolean = false

    override fun isPrivate(): Boolean = true

    override fun isProtected(): Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovyParserFieldDeclaration) return false
        return name == other.name && declaringType.qualifiedName == other.declaringType.qualifiedName
    }

    override fun hashCode(): Int = 31 * name.hashCode() + declaringType.qualifiedName.hashCode()

    override fun toString(): String = "GroovyParserFieldDeclaration[$name: ${type.describe()}]"
}
