package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Represents a declaration that has a value and type (field, parameter, variable).
 */
interface ResolvedValueDeclaration : ResolvedDeclaration {

    /**
     * The type of this value.
     */
    val type: ResolvedType
}
