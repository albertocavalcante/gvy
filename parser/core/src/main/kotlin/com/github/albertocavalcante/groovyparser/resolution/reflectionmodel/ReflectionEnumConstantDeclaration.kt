package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedEnumConstantDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedEnumDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * A resolved enum constant declaration backed by Java reflection.
 */
class ReflectionEnumConstantDeclaration(
    private val enumConstant: Enum<*>,
    override val declaringType: ResolvedEnumDeclaration,
) : ResolvedEnumConstantDeclaration {

    override val name: String = enumConstant.name

    override val type: ResolvedReferenceType
        get() = ResolvedReferenceType(declaringType)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionEnumConstantDeclaration) return false
        return enumConstant == other.enumConstant
    }

    override fun hashCode(): Int = enumConstant.hashCode()

    override fun toString(): String = "ReflectionEnumConstantDeclaration[$name]"
}
