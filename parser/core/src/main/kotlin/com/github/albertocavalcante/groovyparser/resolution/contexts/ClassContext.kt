package com.github.albertocavalcante.groovyparser.resolution.contexts

import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.Context
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Context for a class declaration.
 *
 * Handles:
 * - Field resolution
 * - Method resolution
 * - Type parameters (for generics)
 */
class ClassContext(
    private val classDeclaration: ClassDeclaration,
    private val resolvedClass: ResolvedTypeDeclaration,
    override val parent: Context,
    override val typeSolver: TypeSolver,
) : Context {

    override fun solveSymbol(name: String): SymbolReference<ResolvedValueDeclaration> {
        // Check fields
        val field = classDeclaration.fields.find { it.name == name }
        if (field != null) {
            return SymbolReference.solved(
                GroovyParserFieldDeclaration(field, resolvedClass, typeSolver),
            )
        }

        // Delegate to parent
        return parent.solveSymbol(name)
    }

    override fun solveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // TODO: Check nested classes
        // Delegate to parent
        return parent.solveType(name)
    }

    override fun solveMethod(
        name: String,
        argumentTypes: List<ResolvedType>,
    ): SymbolReference<ResolvedMethodDeclaration> {
        // Find method in this class
        val methods = resolvedClass.getDeclaredMethods().filter { it.name == name }

        for (method in methods) {
            if (isMethodApplicable(method, argumentTypes)) {
                return SymbolReference.solved(method)
            }
        }

        // Check superclass
        if (resolvedClass.isClass()) {
            val classDecl = resolvedClass.asClass()
            classDecl.superClass?.let { superClass ->
                for (method in superClass.declaration.getDeclaredMethods()) {
                    if (method.name == name && isMethodApplicable(method, argumentTypes)) {
                        return SymbolReference.solved(method)
                    }
                }
            }
        }

        // Delegate to parent
        return parent.solveMethod(name, argumentTypes)
    }

    private fun isMethodApplicable(method: ResolvedMethodDeclaration, argumentTypes: List<ResolvedType>): Boolean {
        // Basic check: parameter count must match
        if (method.getNumberOfParams() != argumentTypes.size) {
            return false
        }

        // Check each parameter type
        val params = method.getParameters()
        for (i in argumentTypes.indices) {
            val paramType = params[i].type
            val argType = argumentTypes[i]
            if (!paramType.isAssignableBy(argType)) {
                return false
            }
        }

        return true
    }

    override fun solveGenericType(name: String): ResolvedType? {
        // Check type parameters
        val typeParams = resolvedClass.getTypeParameters()
        for (typeParam in typeParams) {
            if (typeParam.name == name) {
                val bounds = typeParam.getBounds()
                return bounds.firstOrNull()
            }
        }
        return parent.solveGenericType(name)
    }
}
