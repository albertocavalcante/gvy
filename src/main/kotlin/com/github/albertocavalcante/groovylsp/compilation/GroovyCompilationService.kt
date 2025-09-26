package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstCache
import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.dsl.RangeBuilder
import com.github.albertocavalcante.groovylsp.dsl.diagnostic
import com.github.albertocavalcante.groovylsp.errors.AstGenerationException
import com.github.albertocavalcante.groovylsp.errors.CacheCorruptionException
import com.github.albertocavalcante.groovylsp.errors.CircularReferenceException
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.ResourceExhaustionException
import com.github.albertocavalcante.groovylsp.errors.syntaxError
import com.github.albertocavalcante.groovylsp.providers.symbols.SymbolStorage
import com.github.albertocavalcante.groovylsp.providers.symbols.buildFromVisitor
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

    // AST visitor and symbol table caches with LRU eviction
    private val astVisitorCache = LRUCache<URI, AstVisitor>(maxSize = 100)
    private val symbolTableCache = LRUCache<URI, SymbolTable>(maxSize = 100)
    private val symbolStorageCache = LRUCache<URI, SymbolStorage>(maxSize = 100)

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

            // Try to extract more specific error information
            val specificException = when {
                e.message?.contains("Syntax error", ignoreCase = true) == true -> {
                    // Extract line/column if available from error message
                    val lineColumn = extractLineColumnFromMessage(e.message)
                    uri.syntaxError(
                        lineColumn?.first ?: 0,
                        lineColumn?.second ?: 0,
                        e.message ?: "Unknown syntax error",
                        e,
                    )
                }
                else -> AstGenerationException(uri, e.message ?: "Unknown compilation error", e)
            }

            val errorDiagnostic = createErrorDiagnostic(
                "${specificException.javaClass.simpleName}: ${specificException.message}",
            )
            CompilationResult.failure(listOf(errorDiagnostic))
        } catch (e: GroovyLspException) {
            logger.error("LSP error during compilation for $uri", e)
            val errorDiagnostic = createErrorDiagnostic("LSP error: ${e.message}")
            CompilationResult.failure(listOf(errorDiagnostic))
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments during compilation for $uri", e)
            val errorDiagnostic = createErrorDiagnostic("Invalid arguments: ${e.message}")
            CompilationResult.failure(listOf(errorDiagnostic))
        } catch (e: IllegalStateException) {
            logger.error("Invalid state during compilation for $uri", e)
            val errorDiagnostic = createErrorDiagnostic("Invalid state: ${e.message}")
            CompilationResult.failure(listOf(errorDiagnostic))
        } catch (e: java.io.IOException) {
            logger.error("I/O error during compilation for $uri", e)
            val errorDiagnostic = createErrorDiagnostic("I/O error: ${e.message}")
            CompilationResult.failure(listOf(errorDiagnostic))
        } catch (e: RuntimeException) {
            logger.error("Runtime error during compilation for $uri", e)
            val errorDiagnostic = createErrorDiagnostic("Runtime error: ${e.message}")
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

            // Build AST visitor and symbol table for go-to-definition
            buildAstVisitorAndSymbolTable(uri, compilationUnit, sourceUnit)

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
     * Gets the AST visitor for a URI, or null if not available.
     */
    fun getAstVisitor(uri: URI): AstVisitor? = astVisitorCache.get(uri)

    /**
     * Gets the symbol table for a URI, or null if not available.
     */
    fun getSymbolTable(uri: URI): SymbolTable? = symbolTableCache.get(uri)

    /**
     * Gets the symbol storage for a URI, or null if not available.
     */
    fun getSymbolStorage(uri: URI): SymbolStorage? = symbolStorageCache.get(uri)

    /**
     * Clears all caches.
     */
    fun clearCaches() {
        astCache.clear()
        diagnosticsCache.clear()
        astVisitorCache.clear()
        symbolTableCache.clear()
        symbolStorageCache.clear()
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
     * Build AST visitor and symbol table for go-to-definition functionality
     */
    private fun buildAstVisitorAndSymbolTable(uri: URI, compilationUnit: CompilationUnit, sourceUnit: SourceUnit) {
        try {
            // Create and configure AST visitor
            val astVisitor = AstVisitor()

            // Get the module from the compilation unit
            val ast = compilationUnit.ast
            if (ast?.modules?.isNotEmpty() == true) {
                val module = ast.modules.first()

                // Visit the module to build the AST relationships
                astVisitor.visitModule(module, sourceUnit, uri)

                // Cache the visitor
                astVisitorCache.put(uri, astVisitor)

                // Create and build symbol table
                val symbolTable = SymbolTable()
                symbolTable.buildFromVisitor(astVisitor)

                // Cache the symbol table
                symbolTableCache.put(uri, symbolTable)

                // Create and build type-safe symbol storage
                val symbolStorage = SymbolStorage()
                val updatedSymbolStorage = symbolStorage.buildFromVisitor(astVisitor)

                // Cache the symbol storage
                symbolStorageCache.put(uri, updatedSymbolStorage)

                logger.debug("Built AST visitor and symbol table for $uri with ${astVisitor.getAllNodes().size} nodes")
            } else {
                logger.debug("No modules available for AST visitor creation: $uri")
            }
        } catch (e: OutOfMemoryError) {
            logger.error("Out of memory building AST visitor and symbol table for $uri", e)
            val specificException = ResourceExhaustionException("Memory", -1, -1)
            logger.warn("Specific exception type: ${specificException.javaClass.simpleName}")
        } catch (e: StackOverflowError) {
            logger.error("Stack overflow building AST visitor and symbol table for $uri", e)
            val specificException = CircularReferenceException(
                "AST traversal",
                listOf("visitModule", "buildSymbolTable"),
            )
            logger.warn("Specific exception type: ${specificException.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments building AST visitor and symbol table for $uri", e)
            val specificException = CacheCorruptionException(
                "AST visitor/symbol table",
                e.message ?: "Invalid arguments",
                e,
            )
            logger.warn("Specific exception type: ${specificException.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            logger.error("Invalid state building AST visitor and symbol table for $uri", e)
            val specificException =
                CacheCorruptionException("AST visitor/symbol table", e.message ?: "Invalid state", e)
            logger.warn("Specific exception type: ${specificException.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            logger.error("Runtime error building AST visitor and symbol table for $uri", e)
            val specificException =
                CacheCorruptionException("AST visitor/symbol table", e.message ?: "Runtime error", e)
            logger.warn("Specific exception type: ${specificException.javaClass.simpleName}")
        }
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

    /**
     * Extract line and column information from Groovy compiler error messages.
     * Groovy error messages often contain position information like "@ line 5, column 10"
     */
    private fun extractLineColumnFromMessage(message: String?): Pair<Int, Int>? {
        if (message == null) return null

        // Look for patterns like "@ line 5, column 10" or "line: 5, column: 10"
        val lineColumnRegex = """(?:@\s*)?line[:\s]*(\d+)(?:[,\s]*column[:\s]*(\d+))?""".toRegex(
            RegexOption.IGNORE_CASE,
        )
        val match = lineColumnRegex.find(message)

        return match?.let { matchResult ->
            val line = matchResult.groups[1]?.value?.toIntOrNull() ?: 0
            val column = matchResult.groups[2]?.value?.toIntOrNull() ?: 0
            line to column
        }
    }

    // Expose cache for testing purposes
    internal val cache: AstCache get() = astCache
}
