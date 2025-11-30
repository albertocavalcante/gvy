package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class GroovyCompilationService {

    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val cache = CompilationCache()
    private val errorHandler = CompilationErrorHandler()
    private val parser = GroovyParserFacade()
    private val symbolStorageCache = LRUCache<URI, SymbolIndex>(maxSize = 100)

    // Track ongoing compilation per URI for proper async coordination
    private val compilationJobs = ConcurrentHashMap<URI, Deferred<CompilationResult>>()

    val workspaceManager = WorkspaceManager()

    /**
     * Compiles Groovy source code and returns the result.
     */
    @Suppress("TooGenericExceptionCaught") // Final fallback
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

        // Get file-specific classpath (may be Jenkins-specific or standard)
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        val parseResult = parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                useRecursiveVisitor = true,
            ),
        )

        val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
        val ast = parseResult.ast

        val result = if (ast != null) {
            cache.put(uri, content, parseResult)
            symbolStorageCache.put(uri, SymbolIndex().buildFromVisitor(parseResult.astModel))
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

    fun getAstModel(uri: URI): GroovyAstModel? = getParseResult(uri)?.astModel

    fun getSymbolTable(uri: URI): SymbolTable? = getParseResult(uri)?.symbolTable

    fun getSymbolStorage(uri: URI): SymbolIndex? {
        symbolStorageCache.get(uri)?.let { return it }
        val visitor = getAstModel(uri) ?: return null
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

    /**
     * Start async compilation and return Deferred for coordination.
     * Reuses existing compilation if already in progress for the same URI.
     */
    fun compileAsync(scope: CoroutineScope, uri: URI, content: String): Deferred<CompilationResult> {
        // Check if already compiling this document
        compilationJobs[uri]?.let { existing ->
            if (existing.isActive) {
                logger.debug("Reusing active compilation for $uri")
                return existing
            }
        }

        // Start new compilation
        val deferred = scope.async(Dispatchers.IO) {
            try {
                compile(uri, content)
            } finally {
                compilationJobs.remove(uri)
            }
        }

        compilationJobs[uri] = deferred
        return deferred
    }

    /**
     * Ensure document is compiled, awaiting if compilation is in progress.
     * Returns immediately if document is already cached.
     * Returns null if document is not cached and not currently compiling.
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        // If currently compiling, await it
        compilationJobs[uri]?.let { deferred ->
            logger.debug("Awaiting active compilation for $uri")
            return deferred.await()
        }

        // Check if already in cache
        cache.getWithContent(uri)?.let { (cachedContent, parseResult) ->
            logger.debug("Using cached compilation for $uri")
            val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
            val ast = parseResult.ast
            val sourceText = cachedContent
            return if (ast != null) {
                CompilationResult.success(ast, diagnostics, sourceText)
            } else {
                CompilationResult.failure(diagnostics, sourceText)
            }
        }

        // Not compiling and not cached
        logger.debug("No compilation found for $uri (not cached, not compiling)")
        return null
    }

    fun clearCaches() {
        cache.clear()
        symbolStorageCache.clear()
        compilationJobs.clear()
    }

    fun getCacheStatistics() = cache.getStatistics()

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>) {
        val changed = workspaceManager.updateWorkspaceModel(workspaceRoot, dependencies, sourceDirectories)
        if (changed) {
            clearCaches()
        }
    }

    fun createContext(uri: URI): CompilationContext? {
        val parseResult = getParseResult(uri) ?: return null
        val ast = parseResult.ast ?: return null

        return CompilationContext(
            uri = uri,
            moduleNode = ast,
            compilationUnit = parseResult.compilationUnit,
            astModel = parseResult.astModel,
            workspaceRoot = workspaceManager.getWorkspaceRoot(),
            classpath = workspaceManager.getDependencyClasspath(),
        )
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

    // Expose cache for testing purposes
    internal val astCache get() = cache
}
