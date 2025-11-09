package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.errors.CacheCorruptionException
import com.github.albertocavalcante.groovylsp.errors.CircularReferenceException
import com.github.albertocavalcante.groovylsp.errors.ResourceExhaustionException
import com.github.albertocavalcante.groovylsp.gradle.DependencyResolver
import com.github.albertocavalcante.groovylsp.gradle.GradleDependencyResolver
import com.github.albertocavalcante.groovylsp.providers.symbols.SymbolStorage
import com.github.albertocavalcante.groovylsp.providers.symbols.buildFromVisitor
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.streams.toList

/**
 * Service for compiling Groovy source code and managing ASTs.
 * Refactored to use composition with cache and error handler components.
 */
class GroovyCompilationService {

    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val cache = CompilationCache()
    private val errorHandler = CompilationErrorHandler()
    private val dependencyResolver: DependencyResolver = GradleDependencyResolver()
    private val parser = GroovyParserFacade()

    // AST visitor and symbol table caches with LRU eviction
    private val astVisitorCache = LRUCache<URI, AstVisitor>(maxSize = 100)
    private val symbolTableCache = LRUCache<URI, SymbolTable>(maxSize = 100)
    private val symbolStorageCache = LRUCache<URI, SymbolStorage>(maxSize = 100)

    // Dependency classpath management
    private val dependencyClasspath = mutableListOf<Path>()
    private var workspaceRoot: Path? = null
    private val sourceRoots = mutableSetOf<Path>()
    private var workspaceSources: List<Path> = emptyList()

    /**
     * Compiles Groovy source code and returns the result.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - final fallback
    suspend fun compile(uri: URI, content: String): CompilationResult {
        logger.debug("Compiling: $uri")

        return try {
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
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        val parseResult = parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = dependencyClasspath.toList(),
                sourceRoots = sourceRoots.toList(),
                workspaceSources = workspaceSources,
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
            ),
        )

        val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
        val ast = parseResult.ast
        val compilationUnit = parseResult.compilationUnit
        val sourceUnit = parseResult.sourceUnit

        val result = if (ast != null) {
            // We have an AST, cache it and the diagnostics.
            cache.cacheResult(uri, content, ast, diagnostics, compilationUnit)

            // Build AST visitor and symbol table for go-to-definition
            buildAstVisitorAndSymbolTable(uri, compilationUnit, sourceUnit)

            // Success is true only if there are no error-level diagnostics.
            val isSuccess = parseResult.isSuccessful
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

    /**
     * Gets the symbol table for a URI, or null if not available.
     */
    fun getSymbolTable(uri: URI): SymbolTable? = symbolTableCache.get(uri)

    /**
     * Gets the symbol storage for a URI, or null if not available.
     */
    fun getSymbolStorage(uri: URI): SymbolStorage? = symbolStorageCache.get(uri)

    /**
     * Returns all known symbol storages keyed by their URI.
     */
    fun getAllSymbolStorages(): Map<URI, SymbolStorage> = symbolStorageCache.snapshot()

    /**
     * Clears all caches.
     */
    fun clearCaches() {
        cache.clear()
        astVisitorCache.clear()
        symbolTableCache.clear()
        symbolStorageCache.clear()
    }

    fun getCacheStatistics() = cache.getStatistics()

    /**
     * Initialize the workspace without resolving dependencies (non-blocking).
     * Dependencies will be updated separately via updateDependencies().
     */
    fun initializeWorkspace(workspaceRoot: Path) {
        logger.info("Initializing workspace (non-blocking): $workspaceRoot")
        this.workspaceRoot = workspaceRoot
        refreshSourceRoots(workspaceRoot)
        refreshWorkspaceSources()
    }

    /**
     * Updates the dependency classpath with pre-resolved dependencies.
     * Clears compilation caches since the classpath has changed.
     *
     * @param newDependencies The list of resolved dependency paths
     */
    fun updateDependencies(newDependencies: List<Path>) {
        if (newDependencies.size != dependencyClasspath.size ||
            newDependencies.toSet() != dependencyClasspath.toSet()
        ) {
            dependencyClasspath.clear()
            dependencyClasspath.addAll(newDependencies)

            logger.info("Updated dependency classpath with ${dependencyClasspath.size} dependencies")

            // Clear caches since classpath has changed
            clearCaches()
        } else {
            logger.debug("Dependencies unchanged, no cache clearing needed")
        }
    }

    /**
     * Legacy method - Updates the dependency classpath by resolving dependencies from the workspace.
     * @deprecated Use updateDependencies(List<Path>) with pre-resolved dependencies instead.
     */
    @Deprecated("Use updateDependencies(List<Path>) for non-blocking resolution")
    fun updateDependencies() {
        val root = workspaceRoot
        if (root == null) {
            logger.debug("No workspace root set, skipping dependency resolution")
            return
        }

        logger.info("Resolving dependencies for workspace: $root")
        val resolution = dependencyResolver.resolve(root)
        updateWorkspaceModel(root, resolution.dependencies, resolution.sourceDirectories)
    }

    /**
     * Gets the current dependency classpath.
     */
    fun getDependencyClasspath(): List<Path> = dependencyClasspath.toList()

    fun getWorkspaceRoot(): Path? = workspaceRoot

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>) {
        this.workspaceRoot = workspaceRoot

        val depsChanged = dependencies.toSet() != dependencyClasspath.toSet()
        val sourcesChanged = sourceDirectories.toSet() != sourceRoots
        if (depsChanged) {
            dependencyClasspath.clear()
            dependencyClasspath.addAll(dependencies)
            logger.info("Updated dependency classpath with ${dependencyClasspath.size} dependencies")
        }

        sourceRoots.clear()
        if (sourceDirectories.isNotEmpty()) {
            sourceDirectories.forEach(sourceRoots::add)
            logger.info("Received ${sourceRoots.size} source roots from build model")
        } else {
            refreshSourceRoots(workspaceRoot)
        }

        refreshWorkspaceSources()

        if (depsChanged || sourcesChanged) {
            clearCaches()
        }
    }

    private fun refreshSourceRoots(root: Path) {
        if (sourceRoots.isEmpty()) {
            val candidates = listOf(
                root.resolve("src/main/groovy"),
                root.resolve("src/main/java"),
                root.resolve("src/main/kotlin"),
                root.resolve("src/test/groovy"),
            )

            candidates.filter { Files.exists(it) && it.isDirectory() }.forEach(sourceRoots::add)

            logger.info("Indexed ${sourceRoots.size} source roots: ${sourceRoots.joinToString { it.toString() }}")
        }
    }

    private fun refreshWorkspaceSources() {
        workspaceSources = sourceRoots.flatMap { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@flatMap emptyList<Path>()
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.extension.equals("groovy", ignoreCase = true) }
                    .toList()
            }
        }

        logger.info("Indexed ${workspaceSources.size} Groovy sources from workspace roots")
    }

    private fun buildLocatorCandidates(uri: URI, sourcePath: Path?): Set<String> {
        val candidates = mutableSetOf<String>()
        candidates += uri.toString()
        candidates += uri.path
        sourcePath?.let { path ->
            candidates += path.toString()
            candidates += path.toAbsolutePath().toString()
        }
        return candidates
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
}
