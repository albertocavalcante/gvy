package com.github.albertocavalcante.groovyparser.resolution.declarations

/**
 * Represents a resolved parameter declaration.
 */
interface ResolvedParameterDeclaration : ResolvedValueDeclaration {

    /**
     * Returns true if this parameter is variadic (varargs).
     */
    fun isVariadic(): Boolean = false
}
