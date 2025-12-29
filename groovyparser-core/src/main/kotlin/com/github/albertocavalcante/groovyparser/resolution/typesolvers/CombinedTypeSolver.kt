package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import java.util.concurrent.ConcurrentHashMap

/**
 * A type solver that combines multiple child type solvers.
 *
 * When resolving a type, it tries each child solver in order until one succeeds.
 * Results are cached for performance.
 *
 * @param solvers The initial child solvers to add
 */
class CombinedTypeSolver(vararg solvers: TypeSolver) : TypeSolver {

    override var parent: TypeSolver? = null

    private val childSolvers = mutableListOf<TypeSolver>()
    private val cache = ConcurrentHashMap<String, SymbolReference<ResolvedTypeDeclaration>>()

    init {
        solvers.forEach { add(it) }
    }

    /**
     * Adds a child type solver.
     *
     * @param solver The solver to add
     * @param resetCache Whether to clear the cache after adding (default: true)
     */
    fun add(solver: TypeSolver, resetCache: Boolean = true) {
        childSolvers.add(solver)
        solver.parent = this
        if (resetCache) {
            cache.clear()
        }
    }

    override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // Check cache first
        cache[name]?.let { return it }

        // Try each child solver
        for (solver in childSolvers) {
            try {
                val ref = solver.tryToSolveType(name)
                if (ref.isSolved) {
                    cache[name] = ref
                    return ref
                }
            } catch (e: Exception) {
                // Continue to next solver on error
            }
        }

        // Cache the unsolved result too
        val unsolved = SymbolReference.unsolved<ResolvedTypeDeclaration>()
        cache[name] = unsolved
        return unsolved
    }

    /**
     * Clears the type cache.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Returns the number of child solvers.
     */
    fun getSolverCount(): Int = childSolvers.size

    override fun toString(): String = "CombinedTypeSolver[${childSolvers.size} solvers]"
}
