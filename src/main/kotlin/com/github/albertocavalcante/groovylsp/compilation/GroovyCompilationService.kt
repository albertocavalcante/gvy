package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstCache
import com.github.albertocavalcante.groovylsp.dsl.RangeBuilder
import com.github.albertocavalcante.groovylsp.dsl.diagnostic
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for compiling Groovy source code and managing ASTs.
 */
class GroovyCompilationService {

    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val astCache = AstCache()
    private val diagnosticsCache = ConcurrentHashMap<URI, List<Diagnostic>>()

    /**
     * Compiles Groovy source code and returns the result.
     */
    suspend fun compile(uri: URI, content: String): CompilationResult {
        logger.debug("Compiling: $uri")

        return try {
            // Check cache first
            val cachedAst = astCache.get(uri, content)
            if (cachedAst != null) {
                logger.debug("Using cached AST for: $uri")
                val cachedDiagnostics = diagnosticsCache[uri] ?: emptyList()
                CompilationResult.success(cachedAst, cachedDiagnostics)
            } else {
                performCompilation(uri, content)
            }
        } catch (e: CompilationFailedException) {
            logger.error("Compilation failed for $uri", e)
            val errorDiagnostic = createErrorDiagnostic("Compilation error: ${e.message}")
            CompilationResult.failure(listOf(errorDiagnostic))
        }
    }

    private suspend fun performCompilation(uri: URI, content: String): CompilationResult {
        // Create compiler configuration
        val config = createCompilerConfiguration()

        // Create compilation unit
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)

        // Add source
        val fileName = uri.path.substringAfterLast('/')
        val source = StringReaderSource(content, config)
        val sourceUnit = SourceUnit(fileName, source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        // Compile to canonicalization phase (sufficient for most LSP features)
        try {
            compilationUnit.compile(Phases.CANONICALIZATION)
        } catch (e: CompilationFailedException) {
            // Expected for files with syntax errors - we still want to extract diagnostics
            logger.debug("Compilation failed for $uri: ${e.message}")
        }

        // Extract diagnostics from error collector
        val diagnostics = DiagnosticConverter.convertErrorCollector(compilationUnit.errorCollector)

        // Get the AST
        val ast = getAstFromCompilationUnit(compilationUnit)

        val result = if (ast != null) {
            // We have an AST, cache it and the diagnostics.
            astCache.put(uri, content, ast)
            diagnosticsCache[uri] = diagnostics
            // Success is true only if there are no error-level diagnostics.
            val isSuccess = diagnostics.none { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
            CompilationResult(isSuccess, ast, diagnostics)
        } else {
            // No AST, compilation definitely failed.
            diagnosticsCache[uri] = diagnostics
            CompilationResult.failure(diagnostics)
        }

        logger.debug("Compilation result for $uri: success=${result.isSuccess}, diagnostics=${diagnostics.size}")
        return result
    }

    /**
     * Gets the cached AST for a URI, or null if not cached.
     * Note: This method doesn't validate cache freshness - use compile() for that.
     */
    fun getAst(uri: URI): ASTNode? = astCache.getUnchecked(uri)

    /**
     * Gets the cached diagnostics for a URI.
     */
    fun getDiagnostics(uri: URI): List<Diagnostic> = diagnosticsCache[uri] ?: emptyList()

    /**
     * Clears all caches.
     */
    fun clearCaches() {
        astCache.clear()
        diagnosticsCache.clear()
    }

    /**
     * Creates a compiler configuration optimized for language server use.
     */
    private fun createCompilerConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        // We want to compile to AST analysis but not generate bytecode
        targetDirectory = null

        // Enable debugging information for better diagnostics
        debug = true

        // Set optimization options for better error reporting
        optimizationOptions = mapOf(
            CompilerConfiguration.GROOVYDOC to true,
        )

        // Set encoding
        sourceEncoding = "UTF-8"
    }

    /**
     * Extracts the AST from a CompilationUnit.
     */
    private fun getAstFromCompilationUnit(compilationUnit: CompilationUnit): ASTNode? = try {
        // Get the AST from the compilation unit
        val ast = compilationUnit.ast
        if (ast != null && ast.modules.isNotEmpty()) {
            ast.modules.first()
        } else {
            logger.debug("No modules found in compilation unit AST")
            null
        }
    } catch (e: CompilationFailedException) {
        logger.debug("Failed to extract AST from compilation unit: ${e.message}")
        null
    }

    /**
     * Creates an error diagnostic for unexpected compilation errors.
     */
    private fun createErrorDiagnostic(message: String): Diagnostic = diagnostic {
        range(RangeBuilder.at(0, 0))
        error(message)
        source("groovy-lsp")
        code("compilation-error")
    }

    // Expose cache for testing purposes
    internal val cache: AstCache get() = astCache
}
