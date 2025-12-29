package com.github.albertocavalcante.groovyparser.resolution.contexts

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.resolution.Context
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Context for a compilation unit (Groovy source file).
 *
 * Handles:
 * - Package-level type resolution
 * - Import resolution
 * - Default imports (java.lang, groovy.lang, etc.)
 */
class CompilationUnitContext(private val compilationUnit: CompilationUnit, override val typeSolver: TypeSolver) :
    Context {

    override val parent: Context? = null

    override fun solveSymbol(name: String): SymbolReference<ResolvedValueDeclaration> {
        // Compilation unit level doesn't have symbols (fields/variables)
        return SymbolReference.unsolved()
    }

    override fun solveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // 1. Check explicit imports
        for (import in compilationUnit.imports) {
            if (!import.isStarImport && import.name.endsWith(".$name")) {
                val ref = typeSolver.tryToSolveType(import.name)
                if (ref.isSolved) return ref
            }
        }

        // 2. Check star imports
        for (import in compilationUnit.imports) {
            if (import.isStarImport) {
                val packageName = import.name.removeSuffix(".*")
                val fqn = "$packageName.$name"
                val ref = typeSolver.tryToSolveType(fqn)
                if (ref.isSolved) return ref
            }
        }

        // 3. Check same package
        val pkg = compilationUnit.packageDeclaration
        if (pkg != null && pkg.isPresent) {
            val samePackageRef = typeSolver.tryToSolveType("${pkg.get().name}.$name")
            if (samePackageRef.isSolved) return samePackageRef
        }

        // 4. Try fully qualified name
        val directRef = typeSolver.tryToSolveType(name)
        if (directRef.isSolved) return directRef

        // 5. Check default imports
        for (defaultImport in DEFAULT_IMPORTS) {
            val fqn = "$defaultImport.$name"
            val ref = typeSolver.tryToSolveType(fqn)
            if (ref.isSolved) return ref
        }

        return SymbolReference.unsolved()
    }

    override fun solveMethod(
        name: String,
        argumentTypes: List<ResolvedType>,
    ): SymbolReference<ResolvedMethodDeclaration> {
        // Static imports could provide methods at compilation unit level
        // For now, return unsolved
        return SymbolReference.unsolved()
    }

    companion object {
        /**
         * Default imports in Groovy (implicitly imported).
         */
        val DEFAULT_IMPORTS = listOf(
            "java.lang",
            "java.util",
            "java.io",
            "java.net",
            "groovy.lang",
            "groovy.util",
            "java.math",
        )
    }
}
