package com.github.albertocavalcante.groovyparser.resolution.groovymodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedArrayType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedVoidType

/**
 * Utility for resolving type names from Groovy AST to resolved types.
 */
object GroovyParserTypeResolver {

    /**
     * Resolves a type name string to a ResolvedType.
     *
     * Handles:
     * - Primitive types (int, boolean, etc.)
     * - void
     * - Array types (int[], String[])
     * - Reference types (String, java.util.List)
     */
    fun resolveType(typeName: String, typeSolver: TypeSolver): ResolvedType {
        val trimmed = typeName.trim()

        return sequenceOf(
            { resolveVoid(trimmed) },
            { resolveArray(trimmed, typeSolver) },
            { resolvePrimitive(trimmed) },
            { resolveDefOrObject(trimmed, typeSolver) },
            { resolveReference(trimmed, typeSolver) },
            { resolveCommonPackage(trimmed, typeSolver) },
        )
            .map { it() }
            .firstOrNull { it != null }
            ?: resolveFallback(typeSolver)
    }

    private fun resolveVoid(name: String): ResolvedType? =
        if (name == "void" || name == "Void") ResolvedVoidType else null

    private fun resolveArray(name: String, typeSolver: TypeSolver): ResolvedType? {
        if (name.endsWith("[]")) {
            val componentTypeName = name.dropLast(2)
            val componentType = resolveType(componentTypeName, typeSolver)
            return ResolvedArrayType(componentType)
        }
        return null
    }

    private fun resolvePrimitive(name: String): ResolvedType? = ResolvedPrimitiveType.byName(name)

    private fun resolveDefOrObject(name: String, typeSolver: TypeSolver): ResolvedType? {
        if (name == "def" || name == "Object") {
            val ref = typeSolver.tryToSolveType("java.lang.Object")
            if (ref.isSolved) return ResolvedReferenceType(ref.getDeclaration())
        }
        return null
    }

    private fun resolveReference(name: String, typeSolver: TypeSolver): ResolvedType? {
        val ref = typeSolver.tryToSolveType(name)
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else null
    }

    private fun resolveCommonPackage(name: String, typeSolver: TypeSolver): ResolvedType? {
        for (commonPackage in COMMON_PACKAGES) {
            val fqn = "$commonPackage.$name"
            val commonRef = typeSolver.tryToSolveType(fqn)
            if (commonRef.isSolved) {
                return ResolvedReferenceType(commonRef.getDeclaration())
            }
        }
        return null
    }

    private fun resolveFallback(typeSolver: TypeSolver): ResolvedType {
        val objectRef = typeSolver.tryToSolveType("java.lang.Object")
        return if (objectRef.isSolved) {
            ResolvedReferenceType(objectRef.getDeclaration())
        } else {
            ResolvedPrimitiveType.INT
        }
    }

    private val COMMON_PACKAGES = listOf(
        "java.lang",
        "java.util",
        "java.io",
        "groovy.lang",
    )
}
