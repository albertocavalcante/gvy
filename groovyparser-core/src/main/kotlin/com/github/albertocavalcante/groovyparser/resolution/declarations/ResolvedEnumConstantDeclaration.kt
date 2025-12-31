package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * Represents a resolved enum constant declaration.
 */
interface ResolvedEnumConstantDeclaration : ResolvedDeclaration {

    /**
     * The enum type that declares this constant.
     */
    val declaringType: ResolvedEnumDeclaration

    /**
     * The type of this enum constant (the enum type itself).
     */
    val type: ResolvedReferenceType
}
