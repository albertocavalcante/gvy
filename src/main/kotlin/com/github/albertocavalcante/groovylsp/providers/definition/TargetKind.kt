package com.github.albertocavalcante.groovylsp.providers.definition

/**
 * Types of targets that can be found during go-to-definition operations.
 */
enum class TargetKind {
    /**
     * The declaration/definition of a symbol.
     */
    DECLARATION,

    /**
     * A reference to a symbol.
     */
    REFERENCE,
}
