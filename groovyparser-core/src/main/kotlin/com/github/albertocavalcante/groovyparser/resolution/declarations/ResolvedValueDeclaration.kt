package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Represents a declaration that has a value and type (field, parameter, variable).
 */
interface ResolvedValueDeclaration : ResolvedDeclaration {

    /**
     * The type of this value.
     */
    val type: ResolvedType

    /**
     * The AST node representing this declaration, if available.
     * Used for navigation to the declaration's source location (go-to-definition).
     * Returns null for declarations from reflection (e.g., JDK classes).
     */
    val declarationNode: Node?
        get() = null
}
