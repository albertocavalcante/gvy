package com.github.albertocavalcante.groovyparser.resolution.reflectionmodel

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedArrayType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedVoidType

/**
 * Factory for creating resolved types from Java reflection classes.
 */
object ReflectionFactory {

    /**
     * Creates a ResolvedType for the given Java class.
     */
    fun typeForClass(clazz: Class<*>, typeSolver: TypeSolver): ResolvedType = when {
        clazz == Void.TYPE -> ResolvedVoidType
        clazz.isPrimitive -> primitiveTypeFor(clazz)
        clazz.isArray -> {
            val componentType = typeForClass(clazz.componentType, typeSolver)
            ResolvedArrayType(componentType)
        }
        else -> {
            val ref = typeSolver.tryToSolveType(clazz.canonicalName ?: clazz.name)
            if (ref.isSolved) {
                ResolvedReferenceType(ref.getDeclaration())
            } else {
                // Create declaration directly for types not in solver
                val declaration = when {
                    clazz.isEnum -> ReflectionEnumDeclaration(clazz, typeSolver)
                    clazz.isInterface -> ReflectionInterfaceDeclaration(clazz, typeSolver)
                    else -> ReflectionClassDeclaration(clazz, typeSolver)
                }
                ResolvedReferenceType(declaration)
            }
        }
    }

    private fun primitiveTypeFor(clazz: Class<*>): ResolvedPrimitiveType = when (clazz) {
        Boolean::class.javaPrimitiveType -> ResolvedPrimitiveType.BOOLEAN
        Byte::class.javaPrimitiveType -> ResolvedPrimitiveType.BYTE
        Char::class.javaPrimitiveType -> ResolvedPrimitiveType.CHAR
        Short::class.javaPrimitiveType -> ResolvedPrimitiveType.SHORT
        Int::class.javaPrimitiveType -> ResolvedPrimitiveType.INT
        Long::class.javaPrimitiveType -> ResolvedPrimitiveType.LONG
        Float::class.javaPrimitiveType -> ResolvedPrimitiveType.FLOAT
        Double::class.javaPrimitiveType -> ResolvedPrimitiveType.DOUBLE
        else -> throw IllegalArgumentException("Not a primitive type: ${clazz.name}")
    }
}
