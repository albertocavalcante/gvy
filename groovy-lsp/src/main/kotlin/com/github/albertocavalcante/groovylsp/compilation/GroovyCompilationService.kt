package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.gradle.DependencyResolver
import com.github.albertocavalcante.groovylsp.gradle.GradleDependencyResolver
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.ast.AstVisitor
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.streams.toList

class GroovyCompilationService {

    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val cache = CompilationCache()
    private val errorHandler = CompilationErrorHandler()
    private val dependencyResolver: DependencyResolver = GradleDependencyResolver()
    private val parser = GroovyParserFacade()
    private val symbolStorageCache = LRUCache<URI, SymbolIndex>(maxSize = 100)

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
            val cachedResult = cache.get(uri, content)
            if (cachedResult != null) {
                logger.debug("Using cached parse result for: $uri")
                val ast = cachedResult.ast!!
                // Convert compilation diagnostics (errors)
                val diagnostics = cachedResult.diagnostics.map { it.toLspDiagnostic() }
                return CompilationResult.success(ast, diagnostics, content)
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

        val result = if (ast != null) {
            cache.put(uri, content, parseResult)
            symbolStorageCache.put(uri, SymbolIndex().buildFromVisitor(parseResult.astVisitor))
            val isSuccess = parseResult.isSuccessful
            CompilationResult(isSuccess, ast, diagnostics, content)
        } else {
            symbolStorageCache.remove(uri)
            CompilationResult.failure(diagnostics, content)
        }

        logger.debug("Compilation result for $uri: success=${result.isSuccess}, diagnostics=${diagnostics.size}")
        return result
    }

    fun getParseResult(uri: URI): com.github.albertocavalcante.groovyparser.api.ParseResult? = cache.get(uri)

    fun getAst(uri: URI): ASTNode? = getParseResult(uri)?.ast

    fun getDiagnostics(uri: URI): List<Diagnostic> =
        getParseResult(uri)?.diagnostics?.map { it.toLspDiagnostic() } ?: emptyList()

    fun getAstVisitor(uri: URI): AstVisitor? = getParseResult(uri)?.astVisitor

    fun getSymbolTable(uri: URI): SymbolTable? = getParseResult(uri)?.symbolTable

    fun getSymbolStorage(uri: URI): SymbolIndex? {
        symbolStorageCache.get(uri)?.let { return it }
        val visitor = getAstVisitor(uri) ?: return null
        val storage = SymbolIndex().buildFromVisitor(visitor)
        symbolStorageCache.put(uri, storage)
        return storage
    }

    fun getAllSymbolStorages(): Map<URI, SymbolIndex> {
        val allStorages = mutableMapOf<URI, SymbolIndex>()
        cache.keys().forEach { uri ->
            getSymbolStorage(uri)?.let { allStorages[uri] = it }
        }
        return allStorages
    }

    fun clearCaches() {
        cache.clear()
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

    // Expose cache for testing purposes
    internal val astCache get() = cache
}
