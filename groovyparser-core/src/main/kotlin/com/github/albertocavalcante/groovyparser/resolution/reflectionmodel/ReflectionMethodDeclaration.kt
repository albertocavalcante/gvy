package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A resolved method declaration backed by Java reflection.
 */
class ReflectionMethodDeclaration(private val method: Method, private val typeSolver: TypeSolver) :
    ResolvedMethodDeclaration {

    override val name: String = method.name

    override val returnType: ResolvedType
        get() = ReflectionFactory.typeForClass(method.returnType, typeSolver)

    override val declaringType: ResolvedTypeDeclaration
        get() {
            val ref = typeSolver.tryToSolveType(method.declaringClass.canonicalName ?: method.declaringClass.name)
            return if (ref.isSolved) {
                ref.getDeclaration()
            } else {
                ReflectionClassDeclaration(
                    method.declaringClass,
                    typeSolver,
                )
            }
        }

    override fun getNumberOfParams(): Int = method.parameterCount

    override fun getParam(index: Int): ResolvedParameterDeclaration = ReflectionParameterDeclaration(
        method.parameters[index],
        typeSolver,
        index == method.parameterCount - 1 && method.isVarArgs,
    )

    override fun getParameters(): List<ResolvedParameterDeclaration> = method.parameters.mapIndexed { index, param ->
        ReflectionParameterDeclaration(param, typeSolver, index == method.parameterCount - 1 && method.isVarArgs)
    }

    override fun isStatic(): Boolean = Modifier.isStatic(method.modifiers)

    override fun isAbstract(): Boolean = Modifier.isAbstract(method.modifiers)

    override fun isFinal(): Boolean = Modifier.isFinal(method.modifiers)

    override fun isPublic(): Boolean = Modifier.isPublic(method.modifiers)

    override fun isPrivate(): Boolean = Modifier.isPrivate(method.modifiers)

    override fun isProtected(): Boolean = Modifier.isProtected(method.modifiers)

    override fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> = method.typeParameters.map {
        ReflectionTypeParameterDeclaration(it, this, typeSolver)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflectionMethodDeclaration) return false
        return method == other.method
    }

    override fun hashCode(): Int = method.hashCode()

    override fun toString(): String = "ReflectionMethodDeclaration[${getSignature()}]"
}
