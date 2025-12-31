package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * A resolved field declaration backed by Java reflection.
 */
class ReflectionFieldDeclaration(private val javaField: Field, private val typeSolver: TypeSolver) :
    ResolvedFieldDeclaration {

    override val name: String = javaField.name

    override val type: ResolvedType
        get() = ReflectionFactory.typeForClass(javaField.type, typeSolver)

    override val declaringType: ResolvedTypeDeclaration
        get() {
            val clazz = javaField.declaringClass
            val ref = typeSolver.tryToSolveType(clazz.canonicalName ?: clazz.name)
            return if (ref.isSolved) ref.getDeclaration() else ReflectionClassDeclaration(clazz, typeSolver)
        }

    override fun isStatic(): Boolean = Modifier.isStatic(javaField.modifiers)

    override fun isFinal(): Boolean = Modifier.isFinal(javaField.modifiers)

    override fun isPublic(): Boolean = Modifier.isPublic(javaField.modifiers)

    override fun isPrivate(): Boolean = Modifier.isPrivate(javaField.modifiers)

    override fun isProtected(): Boolean = Modifier.isProtected(javaField.modifiers)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionFieldDeclaration) return false
        return javaField == other.javaField
    }

    override fun hashCode(): Int = javaField.hashCode()

    override fun toString(): String = "ReflectionFieldDeclaration[$name: ${type.describe()}]"
}
