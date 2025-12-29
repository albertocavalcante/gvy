package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves types from parsed Groovy source files.
 *
 * This solver parses Groovy source files on demand and caches the results.
 *
 * @param sourceRoot The root directory containing Groovy source files
 * @param parser The parser to use (optional, creates a new one if not provided)
 */
class GroovyParserTypeSolver(private val sourceRoot: Path, private val parser: GroovyParser = GroovyParser()) :
    TypeSolver {

    override var parent: TypeSolver? = null

    private val parsedUnits = ConcurrentHashMap<Path, CompilationUnit>()
    private val resolvedTypes = ConcurrentHashMap<String, SymbolReference<ResolvedTypeDeclaration>>()

    override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // Check cache first
        resolvedTypes[name]?.let { return it }

        // Convert qualified name to file path
        val relativePath = name.replace('.', '/') + ".groovy"
        val sourcePath = sourceRoot.resolve(relativePath)

        if (!Files.exists(sourcePath)) {
            // Also try with simple name in root
            val simpleNamePath = sourceRoot.resolve(name.substringAfterLast('.') + ".groovy")
            if (Files.exists(simpleNamePath)) {
                return resolveFromFile(name, simpleNamePath)
            }
            val unsolved = SymbolReference.unsolved<ResolvedTypeDeclaration>()
            resolvedTypes[name] = unsolved
            return unsolved
        }

        return resolveFromFile(name, sourcePath)
    }

    private fun resolveFromFile(name: String, path: Path): SymbolReference<ResolvedTypeDeclaration> {
        val cu = parsedUnits.getOrPut(path) {
            val code = java.nio.file.Files.readString(path)
            val result = parser.parse(code)
            result.result.orElse(null) ?: return cacheAndReturn(name, SymbolReference.unsolved())
        }

        // Find the class in the compilation unit
        val simpleName = name.substringAfterLast('.')
        val classDecl = cu.types
            .filterIsInstance<ClassDeclaration>()
            .find { it.name == simpleName }

        return if (classDecl != null) {
            val declaration = GroovyParserClassDeclaration(classDecl, cu, this)
            cacheAndReturn(name, SymbolReference.solved(declaration))
        } else {
            cacheAndReturn(name, SymbolReference.unsolved())
        }
    }

    private fun cacheAndReturn(
        name: String,
        ref: SymbolReference<ResolvedTypeDeclaration>,
    ): SymbolReference<ResolvedTypeDeclaration> {
        resolvedTypes[name] = ref
        return ref
    }

    /**
     * Clears all caches (parsed units and resolved types).
     */
    fun clearCache() {
        parsedUnits.clear()
        resolvedTypes.clear()
    }

    override fun toString(): String = "GroovyParserTypeSolver[$sourceRoot]"
}
