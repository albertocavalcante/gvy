package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.errors.CacheCorruptionException
import com.github.albertocavalcante.groovylsp.errors.CircularReferenceException
import com.github.albertocavalcante.groovylsp.errors.ResourceExhaustionException
import com.github.albertocavalcante.groovylsp.providers.symbols.SymbolStorage
import com.github.albertocavalcante.groovylsp.providers.symbols.buildFromVisitor
import com.github.albertocavalcante.groovylsp.scanner.TodoCommentScanner
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
import java.io.IOException
import java.net.URI
import java.nio.file.Path

/**
 * Service for compiling Groovy source code and managing ASTs.
 * Refactored to use composition with cache and error handler components.
 *
 * Now uses CentralizedDependencyManager to ensure consistent dependency
 * management across all compilation modes.
 */
@Suppress("TooGenericExceptionCaught") // Compilation service needs robust error handling
class GroovyCompilationService(private val dependencyManager: CentralizedDependencyManager) : DependencyListener {

    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)

    companion object {
        // Debug output limit for dependency logging
        private const val MAX_DEBUG_DEPENDENCIES_TO_LOG = 5
    }

    private val cache = CompilationCache()
    private val errorHandler = CompilationErrorHandler()
    private val todoScanner = TodoCommentScanner()

    // AST visitor and symbol table caches with LRU eviction
    private val astVisitorCache = LRUCache<URI, AstVisitor>(maxSize = 100)
    private val symbolTableCache = LRUCache<URI, SymbolTable>(maxSize = 100)
    private val symbolStorageCache = LRUCache<URI, SymbolStorage>(maxSize = 100)

    // Workspace compilation mode
    private var workspaceCompilationService: WorkspaceCompilationService? = null

    init {
        // Register for dependency updates
        dependencyManager.addListener(this)
        logger.debug("GroovyCompilationService registered for dependency updates")
    }

    /**
     * Called when dependencies are updated. Clears caches to force recompilation
     * with the new classpath.
     */
    override fun onDependenciesUpdated(dependencies: List<Path>) {
        logger.info("GroovyCompilationService received ${dependencies.size} dependencies")
        if (logger.isDebugEnabled) {
            dependencies.take(MAX_DEBUG_DEPENDENCIES_TO_LOG).forEach { dep ->
                logger.debug("  - ${dep.fileName}")
            }
        }

        // Clear all caches since classpath has changed
        clearCaches()
        logger.debug("Cleared compilation caches due to dependency update")
    }

    /**
     * Compiles Groovy source code and returns the result.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - final fallback
    suspend fun compile(uri: URI, content: String): CompilationResult {
        logger.debug("Compiling: $uri")

        return try {
            // Use workspace compilation if enabled
            workspaceCompilationService?.let { workspaceService ->
                logger.debug("Using workspace compilation for: $uri")
                val result = workspaceService.updateFile(uri, content)
                return convertWorkspaceResult(uri, result)
            }

            // Fall back to single-file compilation
            // Check cache first
            val cachedAst = cache.getCachedAst(uri, content)
            if (cachedAst != null) {
                logger.debug("Using cached AST for: $uri")
                val cachedDiagnostics = cache.getCachedDiagnostics(uri)
                CompilationResult.success(cachedAst, cachedDiagnostics, content)
            } else {
                performCompilation(uri, content)
            }
        } catch (e: Exception) {
            errorHandler.handleException(e, uri)
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

        // Compile to semantic analysis phase (required for proper accessedVariable links)
        try {
            compilationUnit.compile(Phases.SEMANTIC_ANALYSIS)
        } catch (e: CompilationFailedException) {
            // Expected for files with syntax errors - we still want to extract diagnostics
            logger.debug("Compilation failed for $uri: ${e.message}")
        }

        // Extract diagnostics from error collector
        val compilationDiagnostics = DiagnosticConverter.convertErrorCollector(compilationUnit.errorCollector)

        // Scan for TODO comments and add to diagnostics (ALWAYS ENABLED FOR DEBUGGING)
        val todoDiagnostics = try {
            logger.debug("TODO SCANNER: Starting scan for $uri")
            val result = todoScanner.scanForTodos(content, uri.toString())
            logger.debug("TODO SCANNER: Found ${result.size} TODO items for $uri")
            result.forEach { diagnostic ->
                logger.debug("TODO SCANNER: - ${diagnostic.message} at line ${diagnostic.range.start.line}")
            }
            result
        } catch (e: IOException) {
            logger.error("TODO SCANNER: IO error scanning $uri", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            logger.error("TODO SCANNER: Invalid input for scanning $uri", e)
            emptyList()
        } catch (e: IllegalStateException) {
            logger.error("TODO SCANNER: Invalid state while scanning $uri", e)
            emptyList()
        }
        val diagnostics = compilationDiagnostics + todoDiagnostics

        logger.debug(
            "Found ${compilationDiagnostics.size} compilation diagnostics " +
                "and ${todoDiagnostics.size} TODO items for $uri",
        )

        // Get the AST
        val ast = getAstFromCompilationUnit(compilationUnit)

        val result = if (ast != null) {
            // We have an AST, cache it and the diagnostics.
            cache.cacheResult(uri, content, ast, diagnostics, compilationUnit)

            // Build AST visitor and symbol table for go-to-definition
            buildAstVisitorAndSymbolTable(uri, compilationUnit, sourceUnit)

            // Success is true only if there are no error-level diagnostics.
            val isSuccess = diagnostics.none { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
            CompilationResult(isSuccess, ast, diagnostics, content)
        } else {
            // No AST, compilation definitely failed.
            CompilationResult.failure(diagnostics, content)
        }

        logger.debug("Compilation result for $uri: success=${result.isSuccess}, diagnostics=${diagnostics.size}")
        return result
    }

    /**
     * Gets the cached AST for a URI, or null if not cached.
     * Note: This method doesn't validate cache freshness - use compile() for that.
     */
    fun getAst(uri: URI): ASTNode? = cache.getCachedAst(uri)

    /**
     * Gets the cached diagnostics for a URI.
     */
    fun getDiagnostics(uri: URI): List<Diagnostic> = cache.getCachedDiagnostics(uri)

    /**
     * Gets the cached CompilationUnit for a URI.
     */
    fun getCompilationUnit(uri: URI): CompilationUnit? = cache.getCachedCompilationUnit(uri)

    /**
     * Gets the AST visitor for a URI, or null if not available.
     */
    fun getAstVisitor(uri: URI): AstVisitor? = astVisitorCache.get(uri)
        ?: workspaceCompilationService?.getAstVisitorForFile(uri)

    /**
     * Gets the symbol table for a URI, or null if not available.
     */
    fun getSymbolTable(uri: URI): SymbolTable? = symbolTableCache.get(uri)
        ?: workspaceCompilationService?.getSymbolTableForFile(uri)

    /**
     * Gets the symbol storage for a URI, or null if not available.
     */
    fun getSymbolStorage(uri: URI): SymbolStorage? = symbolStorageCache.get(uri)

    /**
     * Clears all caches.
     */
    fun clearCaches() {
        cache.clear()
        astVisitorCache.clear()
        symbolTableCache.clear()
        symbolStorageCache.clear()
    }

    /**
     * Gets the current dependency classpath from CentralizedDependencyManager.
     */
    fun getDependencyClasspath(): List<Path> = dependencyManager.getDependencies()

    /**
     * Creates a compiler configuration optimized for language server use.
     */
    private fun createCompilerConfiguration(): CompilerConfiguration {
        val dependencies = dependencyManager.getDependencies()

        return CompilerConfiguration().apply {
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

            // Add dependency JARs to the classpath
            if (dependencies.isNotEmpty()) {
                val classpathString = dependencies.joinToString(System.getProperty("path.separator")) {
                    it.toString()
                }
                setClasspath(classpathString)
                logger.debug("Added ${dependencies.size} dependencies to compiler classpath")
            } else {
                logger.warn("COMPILER CONFIG: No dependencies available - imports will fail!")
            }
        }
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
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - final fallback for AST building
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
        } catch (e: Exception) {
            logger.error("Unexpected error building AST visitor and symbol table for $uri", e)
            val specificException =
                CacheCorruptionException("AST visitor/symbol table", e.message ?: "Unexpected error", e)
            logger.warn("Specific exception type: ${specificException.javaClass.simpleName}")
        }
    }

    // Expose cache for testing purposes
    internal val astCache get() = cache

    /**
     * Enables workspace compilation mode with the given service.
     */
    fun enableWorkspaceMode(workspaceService: WorkspaceCompilationService) {
        this.workspaceCompilationService = workspaceService
        logger.info("Workspace compilation mode enabled")
    }

    /**
     * Disables workspace compilation mode and falls back to single-file compilation.
     */
    fun disableWorkspaceMode() {
        workspaceCompilationService = null
        logger.info("Workspace compilation mode disabled, using single-file compilation")
    }

    /**
     * Converts a workspace compilation result to a single-file compilation result.
     */
    private fun convertWorkspaceResult(
        uri: URI,
        workspaceResult: WorkspaceCompilationService.WorkspaceCompilationResult,
    ): CompilationResult {
        val ast = workspaceResult.modulesByUri[uri]
        val diagnostics = workspaceResult.diagnostics[uri] ?: emptyList()

        return if (ast != null) {
            CompilationResult.success(ast, diagnostics, "")
        } else {
            CompilationResult.failure(diagnostics, "")
        }
    }
}
