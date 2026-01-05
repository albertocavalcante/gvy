package com.github.albertocavalcante.groovyparser.resolution

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.GroovyParserTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import java.nio.file.Path

/**
 * Static utility class for symbol resolution.
 *
 * Provides convenient methods for resolving types without
 * explicitly configuring a type solver.
 *
 * Similar to [com.github.albertocavalcante.groovyparser.StaticGroovyParser].
 */
object StaticGroovyResolver {

    private var defaultSolver: TypeSolver = createDefaultSolver()
    private var resolver: GroovySymbolResolver = GroovySymbolResolver(defaultSolver)

    /**
     * Resolves the type of a node (typically an expression).
     *
     * @param node The node to resolve
     * @return The resolved type
     */
    fun resolveType(node: Node): ResolvedType = resolver.resolveType(node)

    /**
     * Resolves a symbol by name at a given location.
     *
     * @param name The symbol name
     * @param node The AST node providing context
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveSymbol(name: String, node: Node): SymbolReference<ResolvedValueDeclaration> =
        resolver.solveSymbol(name, node)

    /**
     * Resolves a type by name at a given location.
     *
     * @param name The type name
     * @param node The AST node providing context
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveType(name: String, node: Node): SymbolReference<ResolvedTypeDeclaration> = resolver.solveType(name, node)

    /**
     * Resolves a method by name and argument types.
     *
     * @param name The method name
     * @param argumentTypes The argument types
     * @param node The AST node providing context
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveMethod(
        name: String,
        argumentTypes: List<ResolvedType>,
        node: Node,
    ): SymbolReference<ResolvedMethodDeclaration> = resolver.solveMethod(name, argumentTypes, node)

    /**
     * Configures the default type solver.
     *
     * @param block Configuration block for the combined type solver
     */
    fun configure(block: CombinedTypeSolver.() -> Unit) {
        val solver = CombinedTypeSolver(ReflectionTypeSolver())
        solver.block()
        defaultSolver = solver
        resolver = GroovySymbolResolver(solver)
    }

    /**
     * Adds a source root for resolving types from Groovy source files.
     *
     * @param sourceRoot The path to the source root directory
     */
    fun addSourceRoot(sourceRoot: Path) {
        val currentSolver = defaultSolver
        if (currentSolver is CombinedTypeSolver) {
            currentSolver.add(
                GroovyParserTypeSolver(sourceRoot),
            )
        } else {
            val newSolver = CombinedTypeSolver(currentSolver)
            newSolver.add(
                GroovyParserTypeSolver(sourceRoot),
            )
            defaultSolver = newSolver
            resolver = GroovySymbolResolver(newSolver)
        }
    }

    /**
     * Resets the configuration to defaults.
     */
    fun reset() {
        defaultSolver = createDefaultSolver()
        resolver = GroovySymbolResolver(defaultSolver)
    }

    /**
     * Gets the current type solver.
     */
    fun getTypeSolver(): TypeSolver = defaultSolver

    private fun createDefaultSolver(): TypeSolver = CombinedTypeSolver(
        ReflectionTypeSolver(jreOnly = true),
    )
}
