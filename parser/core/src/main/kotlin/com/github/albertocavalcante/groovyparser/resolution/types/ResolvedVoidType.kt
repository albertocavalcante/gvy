package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents the void type.
 *
 * Void is used as the return type of methods that don't return a value.
 */
object ResolvedVoidType : ResolvedType {

    override fun describe(): String = "void"

    override fun isVoid(): Boolean = true

    override fun isAssignableBy(other: ResolvedType): Boolean = other === ResolvedVoidType
}
