package com.github.albertocavalcante.groovyparser.resolution.contexts

import com.github.albertocavalcante.groovyparser.resolution.Context
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Context for a block of statements.
 *
 * Tracks local variables declared within the block.
 */
class BlockContext(override val parent: Context, override val typeSolver: TypeSolver) : Context {

    private val localVariables = mutableMapOf<String, ResolvedValueDeclaration>()

    /**
     * Registers a local variable in this block's scope.
     */
    fun addLocalVariable(name: String, declaration: ResolvedValueDeclaration) {
        localVariables[name] = declaration
    }

    override fun solveSymbol(name: String): SymbolReference<ResolvedValueDeclaration> {
        // Check local variables
        localVariables[name]?.let { return SymbolReference.solved(it) }

        // Delegate to parent
        return parent.solveSymbol(name)
    }

    override fun solveType(name: String): SymbolReference<ResolvedTypeDeclaration> = parent.solveType(name)

    override fun solveMethod(
        name: String,
        argumentTypes: List<ResolvedType>,
    ): SymbolReference<ResolvedMethodDeclaration> = parent.solveMethod(name, argumentTypes)

    override fun solveGenericType(name: String): ResolvedType? = parent.solveGenericType(name)
}
