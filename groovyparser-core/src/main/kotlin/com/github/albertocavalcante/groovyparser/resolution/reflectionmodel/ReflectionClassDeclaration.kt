package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import java.lang.reflect.Modifier

/**
 * A resolved class declaration backed by Java reflection.
 */
class ReflectionClassDeclaration(private val clazz: Class<*>, private val typeSolver: TypeSolver) :
    ResolvedClassDeclaration {

    override val name: String = clazz.simpleName

    override val qualifiedName: String = clazz.canonicalName ?: clazz.name

    override val superClass: ResolvedReferenceType?
        get() {
            val superClazz = clazz.superclass ?: return null
            if (superClazz == Object::class.java && clazz == Object::class.java) {
                return null
            }
            return createReferenceType(superClazz)
        }

    override val interfaces: List<ResolvedReferenceType>
        get() = clazz.interfaces.map { createReferenceType(it) }

    override fun isAbstract(): Boolean = Modifier.isAbstract(clazz.modifiers)

    override fun isFinal(): Boolean = Modifier.isFinal(clazz.modifiers)

    override fun getAncestors(): List<ResolvedReferenceType> {
        val ancestors = mutableListOf<ResolvedReferenceType>()
        superClass?.let { ancestors.add(it) }
        ancestors.addAll(interfaces)
        return ancestors
    }

    override fun getDeclaredFields(): List<ResolvedFieldDeclaration> = clazz.declaredFields.map {
        ReflectionFieldDeclaration(it, typeSolver)
    }

    override fun getDeclaredMethods(): List<ResolvedMethodDeclaration> = clazz.declaredMethods.map {
        ReflectionMethodDeclaration(it, typeSolver)
    }

    override fun getDeclaredConstructors(): List<ResolvedConstructorDeclaration> = clazz.declaredConstructors.map {
        ReflectionConstructorDeclaration(it, typeSolver)
    }

    override fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> = clazz.typeParameters.map {
        ReflectionTypeParameterDeclaration(it, this, typeSolver)
    }

    private fun createReferenceType(clazz: Class<*>): ResolvedReferenceType {
        val ref = typeSolver.tryToSolveType(clazz.canonicalName ?: clazz.name)
        return if (ref.isSolved) {
            ResolvedReferenceType(ref.getDeclaration())
        } else {
            // Fallback: create a declaration directly
            ResolvedReferenceType(ReflectionClassDeclaration(clazz, typeSolver))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionClassDeclaration) return false
        return clazz == other.clazz
    }

    override fun hashCode(): Int = clazz.hashCode()

    override fun toString(): String = "ReflectionClassDeclaration[$qualifiedName]"
}
