package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
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
            val superType = clazz.genericSuperclass ?: return null
            if (superType == Object::class.java) {
                return null
            }
            return convertType(superType) as? ResolvedReferenceType
        }

    override val interfaces: List<ResolvedReferenceType>
        get() = clazz.genericInterfaces.mapNotNull { convertType(it) as? ResolvedReferenceType }

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

    private fun convertType(type: java.lang.reflect.Type): ResolvedType? {
        if (type is Class<*>) {
            return createReferenceType(type)
        }
        if (type is java.lang.reflect.ParameterizedType) {
            val rawType = type.rawType as? Class<*> ?: return null
            val rawRef = createReferenceType(rawType)

            val typeArgs = type.actualTypeArguments.mapNotNull { arg ->
                convertType(arg)
            }

            // If we successfully resolved args, return parameterized type
            if (rawRef is ResolvedReferenceType && typeArgs.size == rawRef.declaration.getTypeParameters().size) {
                return ResolvedReferenceType(rawRef.declaration, typeArgs)
            } else if (rawRef is ResolvedReferenceType) {
                // Fallback if args size mismatch (shouldn't happen for valid reflection)
                // or if we just want best effort
                return ResolvedReferenceType(rawRef.declaration, typeArgs)
            }
            return rawRef
        }
        // [HEURISTIC NOTE]
        // Handle Wildcards, TypeVariables, etc.
        // For `LUB` of concrete types (e.g. Properties), we usually see concrete args.
        // For `? extends T`, we ideally need WildcardType support.
        //
        // Current Strategy: Fallback to 'java.lang.Object'.
        // Trade-off: Safe, but loses precision. `List<? extends String>` becomes `List<Object>`.
        // This is acceptable for current LUB goals but should be improved for full Generics support.
        return resolveType("java.lang.Object")
    }

    private fun resolveType(name: String): ResolvedType? {
        val ref = typeSolver.tryToSolveType(name)
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else null
    }

    private fun createReferenceType(clazz: Class<*>): ResolvedType {
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
