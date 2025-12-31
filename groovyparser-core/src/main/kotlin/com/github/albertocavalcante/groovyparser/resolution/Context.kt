package com.github.albertocavalcante.groovyparser.resolution

import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Represents a context for symbol resolution.
 *
 * Contexts form a hierarchy corresponding to the AST structure:
 * CompilationUnit -> Class -> Method -> Block
 *
 * Each context can resolve symbols in its scope and delegate to parent contexts.
 */
interface Context {

    /**
     * The parent context, or null if this is the root.
     */
    val parent: Context?

    /**
     * The type solver used for resolving type names.
     */
    val typeSolver: TypeSolver

    /**
     * Resolves a symbol (variable, field, parameter) by name.
     *
     * @param name The name of the symbol to resolve
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveSymbol(name: String): SymbolReference<ResolvedValueDeclaration>

    /**
     * Resolves a type by name.
     *
     * @param name The name of the type to resolve (simple or qualified)
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveType(name: String): SymbolReference<ResolvedTypeDeclaration>

    /**
     * Resolves a method by name and argument types.
     *
     * @param name The method name
     * @param argumentTypes The types of the arguments
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveMethod(name: String, argumentTypes: List<ResolvedType>): SymbolReference<ResolvedMethodDeclaration>

    /**
     * Resolves a generic type parameter by name.
     *
     * @param name The name of the type parameter
     * @return The resolved type, or null if not found
     */
    fun solveGenericType(name: String): ResolvedType? = null
}
