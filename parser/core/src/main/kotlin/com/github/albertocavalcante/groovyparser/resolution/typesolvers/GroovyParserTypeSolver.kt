package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import java.io.IOException
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

    override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> = resolvedTypes.getOrPut(name) {
        findSourcePath(name)?.let { path ->
            resolveFromFile(name, path)
        } ?: SymbolReference.unsolved()
    }

    private fun findSourcePath(name: String): Path? {
        val relativePath = name.replace('.', '/') + ".groovy"
        val sourcePath = sourceRoot.resolve(relativePath)
        if (Files.exists(sourcePath)) return sourcePath

        val simpleNamePath = sourceRoot.resolve(name.substringAfterLast('.') + ".groovy")
        return if (Files.exists(simpleNamePath)) simpleNamePath else null
    }

    private fun resolveFromFile(name: String, path: Path): SymbolReference<ResolvedTypeDeclaration> {
        val cu = getOrParse(path) ?: return SymbolReference.unsolved()

        val simpleName = name.substringAfterLast('.')
        val classDecl = cu.types
            .filterIsInstance<ClassDeclaration>()
            .find { it.name == simpleName }

        return if (classDecl != null) {
            val declaration = GroovyParserClassDeclaration(classDecl, cu, this)
            SymbolReference.solved(declaration)
        } else {
            SymbolReference.unsolved()
        }
    }

    private fun getOrParse(path: Path): CompilationUnit? {
        parsedUnits[path]?.let { return it }

        return try {
            val code = Files.readString(path)
            val result = parser.parse(code)
            val cu = result.result.orElse(null)
            if (cu != null) {
                parsedUnits.putIfAbsent(path, cu) ?: cu
            } else {
                null
            }
        } catch (ignored: IOException) {
            null
        }
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
