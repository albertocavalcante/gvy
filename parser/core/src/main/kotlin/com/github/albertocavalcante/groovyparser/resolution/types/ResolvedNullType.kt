package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents the null type.
 *
 * The null type is the type of the null literal. It can be assigned to
 * any reference type but not to primitive types.
 */
object ResolvedNullType : ResolvedType {

    override fun describe(): String = "null"

    override fun isNull(): Boolean = true

    override fun isAssignableBy(other: ResolvedType): Boolean = other.isNull()
}
