package com.github.albertocavalcante.groovyparser.resolution

import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.model.UnsolvedSymbolException

/**
 * Interface for resolving type names to type declarations.
 *
 * Type solvers form a hierarchy where each solver can delegate to its parent.
 * This allows combining multiple sources of type information (reflection, source files, JARs).
 */
interface TypeSolver {

    /**
     * The parent type solver in the hierarchy.
     */
    var parent: TypeSolver?

    /**
     * Returns the root of the type solver hierarchy.
     */
    fun getRoot(): TypeSolver = parent?.getRoot() ?: this

    /**
     * Tries to resolve a type by its fully qualified name.
     *
     * @param name The fully qualified name of the type (e.g., "java.util.List")
     * @return A symbol reference that is either solved or unsolved
     */
    fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration>

    /**
     * Resolves a type by its fully qualified name.
     *
     * @param name The fully qualified name of the type
     * @return The resolved type declaration
     * @throws UnsolvedSymbolException if the type cannot be resolved
     */
    fun solveType(name: String): ResolvedTypeDeclaration {
        val ref = tryToSolveType(name)
        if (ref.isSolved) {
            return ref.getDeclaration()
        }
        throw UnsolvedSymbolException(name, this.toString())
    }

    /**
     * Checks if a type with the given name exists.
     *
     * @param name The fully qualified name of the type
     * @return true if the type can be resolved
     */
    fun hasType(name: String): Boolean = tryToSolveType(name).isSolved

    companion object {
        const val JAVA_LANG_OBJECT = "java.lang.Object"
        const val JAVA_LANG_STRING = "java.lang.String"
        const val JAVA_LANG_CLASS = "java.lang.Class"
    }
}
