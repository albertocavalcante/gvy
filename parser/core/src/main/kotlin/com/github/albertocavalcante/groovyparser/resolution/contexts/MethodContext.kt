package com.github.albertocavalcante.groovyparser.resolution.contexts

import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.Context
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Context for a method declaration.
 *
 * Handles:
 * - Parameter resolution
 * - Local variable resolution (in future)
 * - Type parameters (for generic methods)
 */
class MethodContext(
    private val methodDeclaration: MethodDeclaration,
    override val parent: Context,
    override val typeSolver: TypeSolver,
) : Context {

    override fun solveSymbol(name: String): SymbolReference<ResolvedValueDeclaration> {
        // Check parameters
        val param = methodDeclaration.parameters.find { it.name == name }
        if (param != null) {
            return SymbolReference.solved(
                GroovyParserParameterDeclaration(param, typeSolver),
            )
        }

        // TODO: Check local variables in method body

        // Delegate to parent (class context)
        return parent.solveSymbol(name)
    }

    override fun solveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // Methods don't introduce types, delegate to parent
        return parent.solveType(name)
    }

    override fun solveMethod(
        name: String,
        argumentTypes: List<ResolvedType>,
    ): SymbolReference<ResolvedMethodDeclaration> {
        // Methods don't introduce methods, delegate to parent
        return parent.solveMethod(name, argumentTypes)
    }

    override fun solveGenericType(name: String): ResolvedType? {
        // TODO: Check method type parameters
        return parent.solveGenericType(name)
    }
}
