package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstCache
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilationUnit
import org.eclipse.lsp4j.Diagnostic
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache component for compilation service.
 * Handles AST and diagnostics caching with proper invalidation.
 */
class CompilationCache {
    private val astCache = AstCache()
    private val diagnosticsCache = ConcurrentHashMap<URI, List<Diagnostic>>()
    private val compilationUnitCache = ConcurrentHashMap<URI, CompilationUnit>()

    fun getCachedAst(uri: URI, content: String): ASTNode? = astCache.get(uri, content)

    fun getCachedAst(uri: URI): ASTNode? = astCache.getUnchecked(uri)

    fun getCachedDiagnostics(uri: URI): List<Diagnostic> = diagnosticsCache[uri] ?: emptyList()

    fun getCachedCompilationUnit(uri: URI): CompilationUnit? = compilationUnitCache[uri]

    fun cacheResult(
        uri: URI,
        content: String,
        ast: ASTNode,
        diagnostics: List<Diagnostic>,
        compilationUnit: CompilationUnit,
    ) {
        astCache.put(uri, content, ast)
        diagnosticsCache[uri] = diagnostics
        compilationUnitCache[uri] = compilationUnit
    }

    fun invalidate(uri: URI) {
        astCache.remove(uri)
        diagnosticsCache.remove(uri)
        compilationUnitCache.remove(uri)
    }

    fun clear() {
        astCache.clear()
        diagnosticsCache.clear()
        compilationUnitCache.clear()
    }

    fun getStatistics() = mapOf(
        "cachedAsts" to astCache.size(),
        "cachedDiagnostics" to diagnosticsCache.size,
        "cachedCompilationUnits" to compilationUnitCache.size,
    )
}
