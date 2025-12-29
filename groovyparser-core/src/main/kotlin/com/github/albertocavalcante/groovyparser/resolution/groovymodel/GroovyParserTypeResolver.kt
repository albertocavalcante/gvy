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

        // Check for void
        if (trimmed == "void" || trimmed == "Void") {
            return ResolvedVoidType
        }

        // Check for array types
        if (trimmed.endsWith("[]")) {
            val componentTypeName = trimmed.dropLast(2)
            val componentType = resolveType(componentTypeName, typeSolver)
            return ResolvedArrayType(componentType)
        }

        // Check for primitive types
        ResolvedPrimitiveType.byName(trimmed)?.let { return it }

        // Handle def/Object as Object
        if (trimmed == "def" || trimmed == "Object") {
            val ref = typeSolver.tryToSolveType("java.lang.Object")
            if (ref.isSolved) return ResolvedReferenceType(ref.getDeclaration())
        }

        // Try to resolve as reference type
        val ref = typeSolver.tryToSolveType(trimmed)
        if (ref.isSolved) {
            return ResolvedReferenceType(ref.getDeclaration())
        }

        // Try common imports
        for (commonPackage in COMMON_PACKAGES) {
            val fqn = "$commonPackage.$trimmed"
            val commonRef = typeSolver.tryToSolveType(fqn)
            if (commonRef.isSolved) {
                return ResolvedReferenceType(commonRef.getDeclaration())
            }
        }

        // Fallback to Object for unresolved types (Groovy is dynamic)
        val objectRef = typeSolver.tryToSolveType("java.lang.Object")
        return if (objectRef.isSolved) {
            ResolvedReferenceType(objectRef.getDeclaration())
        } else {
            // Last resort: return primitive Object equivalent
            ResolvedPrimitiveType.INT // This should not happen in practice
        }
    }

    private val COMMON_PACKAGES = listOf(
        "java.lang",
        "java.util",
        "java.io",
        "groovy.lang",
    )
}
