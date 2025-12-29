package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.reflectionmodel.ReflectionClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.reflectionmodel.ReflectionEnumDeclaration
import com.github.albertocavalcante.groovyparser.resolution.reflectionmodel.ReflectionInterfaceDeclaration
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves types using Java reflection.
 *
 * This solver can resolve any type that is available on the classpath at runtime.
 * By default, it only resolves JRE types (java.* and javax.*) to avoid
 * accidentally resolving types that might not be available in the target project.
 *
 * @param jreOnly If true, only resolve types in java.* and javax.* packages
 */
class ReflectionTypeSolver(private val jreOnly: Boolean = true) : TypeSolver {

    override var parent: TypeSolver? = null

    private val classLoader: ClassLoader = ReflectionTypeSolver::class.java.classLoader
    private val cache = ConcurrentHashMap<String, SymbolReference<ResolvedTypeDeclaration>>()

    override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // Filter by JRE packages if configured
        if (jreOnly && !isJreClass(name)) {
            return SymbolReference.unsolved()
        }

        return cache.getOrPut(name) { resolveFromReflection(name) }
    }

    private fun isJreClass(name: String): Boolean = name.startsWith("java.") ||
        name.startsWith("javax.") ||
        name.startsWith("groovy.")

    private fun resolveFromReflection(name: String): SymbolReference<ResolvedTypeDeclaration> = try {
        val clazz = classLoader.loadClass(name)
        val declaration = createDeclaration(clazz)
        SymbolReference.solved(declaration)
    } catch (e: ClassNotFoundException) {
        SymbolReference.unsolved()
    } catch (e: NoClassDefFoundError) {
        SymbolReference.unsolved()
    }

    private fun createDeclaration(clazz: Class<*>): ResolvedTypeDeclaration = when {
        clazz.isEnum -> ReflectionEnumDeclaration(clazz, this)
        clazz.isInterface -> ReflectionInterfaceDeclaration(clazz, this)
        else -> ReflectionClassDeclaration(clazz, this)
    }

    /**
     * Resolves a primitive type to its boxed wrapper type.
     */
    fun solveBoxedType(primitiveName: String): SymbolReference<ResolvedTypeDeclaration> {
        val boxedName = when (primitiveName) {
            "boolean" -> "java.lang.Boolean"
            "byte" -> "java.lang.Byte"
            "char" -> "java.lang.Character"
            "short" -> "java.lang.Short"
            "int" -> "java.lang.Integer"
            "long" -> "java.lang.Long"
            "float" -> "java.lang.Float"
            "double" -> "java.lang.Double"
            else -> return SymbolReference.unsolved()
        }
        return tryToSolveType(boxedName)
    }

    override fun toString(): String = "ReflectionTypeSolver(jreOnly=$jreOnly)"

    companion object {
        /** Predicate that matches all classes. */
        val ALL_CLASSES: (String) -> Boolean = { true }

        /** Predicate that matches only JRE classes. */
        val JRE_ONLY: (String) -> Boolean = { name ->
            name.startsWith("java.") || name.startsWith("javax.")
        }
    }
}
