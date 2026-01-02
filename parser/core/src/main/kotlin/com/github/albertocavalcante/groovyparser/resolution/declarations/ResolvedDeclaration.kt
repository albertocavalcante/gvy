package com.github.albertocavalcante.groovyparser.resolution.declarations

/**
 * Base interface for all resolved declarations.
 *
 * A declaration is any named entity that can be referenced in code,
 * such as types, methods, fields, parameters, and variables.
 */
interface ResolvedDeclaration {
    /**
     * The simple name of this declaration.
     */
    val name: String
}
