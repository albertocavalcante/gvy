package com.github.albertocavalcante.groovyparser.resolution.declarations

/**
 * Represents a resolved enum declaration.
 */
interface ResolvedEnumDeclaration : ResolvedTypeDeclaration {

    override fun isEnum(): Boolean = true

    override fun asEnum(): ResolvedEnumDeclaration = this

    /**
     * Returns the enum constants declared in this enum.
     */
    fun getEnumConstants(): List<ResolvedEnumConstantDeclaration>
}
