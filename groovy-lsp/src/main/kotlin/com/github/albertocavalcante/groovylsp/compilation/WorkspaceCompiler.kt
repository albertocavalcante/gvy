package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.cache.SemanticCache
import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText

/**
 * Error encountered during workspace compilation.
 */
data class CompilationError(val uri: URI, val message: String, val phase: String)

/**
 * Result of workspace compilation containing all compiled modules.
 */
data class WorkspaceCompilationResult(
    val modules: Map<URI, ModuleNode>,
    val errors: List<CompilationError>,
    val success: Boolean,
)

/**
 * Compiles the entire workspace together (instead of per-file) for full cross-file semantic resolution.
 *
 * This compiler addresses the limitation of single-file compilation by compiling all workspace files
 * together using the Groovy compiler's multi-source compilation capabilities. This enables:
 * - Full cross-file type resolution
 * - Cross-file method and field resolution
 * - Proper inheritance chain resolution across files
 *
 * The compiler uses the SEMANTIC_ANALYSIS phase (instead of just CANONICALIZATION) to achieve
 * full type resolution, accepting slower startup time for accuracy.
 *
 * Phase 7 enhancements:
 * - Optional SemanticCache for persistent caching of compilation results
 * - Parallel compilation using proper Dispatchers.Default for CPU-bound work
 * - Cache validation based on source content hashing
 *
 * @param workerSessionManager The worker session manager for executing compilation
 * @param workspaceManager The workspace manager providing workspace sources and configuration
 * @param semanticDb The shared semantic database for symbol storage (must be the same instance used by providers)
 * @param semanticCache Optional semantic cache for persistent storage (improves startup time)
 */
class WorkspaceCompiler(
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val semanticDb: GroovySemanticDB,
    private val semanticCache: SemanticCache? = null,
    val dependencyGraph: DependencyGraph = DependencyGraph(),
) {
    private val logger = LoggerFactory.getLogger(WorkspaceCompiler::class.java)

    /**
     * Cached compilation results. Maps URI to compiled ModuleNode.
     * This cache is updated after each workspace compilation.
     */
    private val moduleCache = mutableMapOf<URI, ModuleNode>()

    /**
     * Lazy-initialized incremental compiler for dependency-aware compilation.
     * Uses the shared semanticDb to ensure symbol visibility across all components.
     */
    private val incrementalCompiler: IncrementalCompiler by lazy {
        IncrementalCompiler(
            workspaceCompiler = this,
            dependencyGraph = dependencyGraph,
            semanticDb = semanticDb,
        )
    }

    /**
     * Whether initial compilation has been performed.
     * Thread-safe using AtomicBoolean to prevent race conditions.
     */
    private val initialCompilationDone = AtomicBoolean(false)

    /**
     * Mutex to ensure only one thread performs initial compilation.
     * Other threads will wait for the initial compilation to complete.
     */
    private val initialCompilationMutex = Mutex()

    /**
     * Compiles all workspace files together for full cross-file semantic resolution.
     *
     * This method:
     * 1. Collects all Groovy source files from the workspace
     * 2. Compiles them together using SEMANTIC_ANALYSIS phase
     * 3. Extracts the ModuleNode for each file
     * 4. Caches the results for later retrieval
     *
     * @return WorkspaceCompilationResult containing all compiled modules and any errors
     */
    suspend fun compileWorkspace(): WorkspaceCompilationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        logger.info("Starting workspace compilation")

        // Get all workspace sources
        val workspaceSources = workspaceManager.getWorkspaceSources()
        logger.info("Found ${workspaceSources.size} workspace sources to compile")

        if (workspaceSources.isEmpty()) {
            logger.info("No workspace sources found, returning empty result")
            return@withContext WorkspaceCompilationResult(
                modules = emptyMap(),
                errors = emptyList(),
                success = true,
            )
        }

        // Compile all files together
        val result = compileFiles(workspaceSources)

        val elapsed = System.currentTimeMillis() - startTime
        logger.info(
            "Workspace compilation completed in ${elapsed}ms: " +
                "${result.modules.size} modules, ${result.errors.size} errors",
        )

        result
    }

    /**
     * Incrementally compiles only the changed files and files that depend on them.
     *
     * Uses dependency tracking to determine which files need recompilation.
     * On first call, performs a full workspace compilation to build the dependency graph.
     *
     * @param changedUris Set of URIs that have changed
     * @return WorkspaceCompilationResult with updated compilation state
     */
    suspend fun incrementalCompile(changedUris: Set<URI>): WorkspaceCompilationResult {
        logger.info("Incremental compile requested for ${changedUris.size} files")

        // On first incremental compile, do initial compilation to build dependency graph
        // Use mutex to ensure only one thread performs initial compilation while others wait
        if (!initialCompilationDone.get()) {
            initialCompilationMutex.withLock {
                // Double-check inside the lock to avoid redundant compilation
                if (initialCompilationDone.compareAndSet(false, true)) {
                    logger.info("First incremental compile - performing initial compilation")
                    val result = incrementalCompiler.initialCompile()
                    return result
                }
            }
        }

        // Perform incremental compilation using dependency tracking
        val incrementalResult = incrementalCompiler.compile(changedUris)

        // Convert to WorkspaceCompilationResult
        return WorkspaceCompilationResult(
            modules = incrementalResult.modules,
            errors = incrementalResult.errors,
            success = incrementalResult.success,
        )
    }

    /**
     * Gets the fully resolved ModuleNode for a file from the cache.
     *
     * @param uri The URI of the file
     * @return The ModuleNode if available, null otherwise
     */
    fun getResolvedModule(uri: URI): ModuleNode? = moduleCache[uri]

    /**
     * Compiles a list of workspace files together.
     *
     * This uses the Groovy compiler's ability to compile multiple sources in a single
     * compilation unit, which enables cross-file resolution.
     */
    private suspend fun compileFiles(sources: List<Path>): WorkspaceCompilationResult {
        val modules = mutableMapOf<URI, ModuleNode>()
        val errors = mutableListOf<CompilationError>()

        // Get workspace configuration
        val classpath = workspaceManager.getDependencyClasspath()
        val sourceRoots = workspaceManager.getSourceRoots()

        // We need to compile each file separately but with all workspace sources available
        // to the compilation unit. This allows the compiler to resolve cross-file references.
        // We do this in parallel for better performance.
        // Use Dispatchers.Default for CPU-bound compilation work
        val results = coroutineScope {
            val compilationJobs = sources.map { sourcePath ->
                async(Dispatchers.Default) {
                    compileFileWithWorkspaceContext(sourcePath, sources, classpath, sourceRoots)
                }
            }

            // Await all compilations
            compilationJobs.awaitAll()
        }

        // Process results
        results.forEach { result ->
            when (result) {
                is FileCompilationResult.Success -> {
                    modules[result.uri] = result.module
                    // Update cache
                    moduleCache[result.uri] = result.module
                }
                is FileCompilationResult.Failure -> {
                    errors.addAll(result.errors)
                }
            }
        }

        val success = errors.isEmpty()
        return WorkspaceCompilationResult(modules, errors, success)
    }

    /**
     * Compiles a single file with the full workspace context.
     *
     * The file is compiled with all workspace sources available to the compilation unit,
     * enabling cross-file resolution.
     */
    private suspend fun compileFileWithWorkspaceContext(
        sourcePath: Path,
        allSources: List<Path>,
        classpath: List<Path>,
        sourceRoots: List<Path>,
    ): FileCompilationResult {
        val uri = sourcePath.toUri()

        return try {
            // Read file content
            val content = sourcePath.readText()

            // Build locator candidates for this file
            val locatorCandidates = buildLocatorCandidates(uri, sourcePath)

            // Create parse request with SEMANTIC_ANALYSIS phase for full type resolution
            val parseRequest = ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = sourceRoots,
                workspaceSources = allSources,
                locatorCandidates = locatorCandidates,
                compilePhase = Phases.SEMANTIC_ANALYSIS,
            )

            // Parse using worker session
            val parseResult = workerSessionManager.parse(parseRequest)

            // Extract module
            val module = parseResult.ast
            if (module != null) {
                logger.debug("Successfully compiled: $uri")
                FileCompilationResult.Success(uri, module)
            } else {
                val errorMessage = if (parseResult.diagnostics.isNotEmpty()) {
                    parseResult.diagnostics.joinToString("; ") { it.message }
                } else {
                    "No AST produced"
                }
                logger.warn("Failed to compile $uri: $errorMessage")
                FileCompilationResult.Failure(
                    listOf(
                        CompilationError(
                            uri = uri,
                            message = errorMessage,
                            phase = "SEMANTIC_ANALYSIS",
                        ),
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error("Exception compiling $uri", e)
            FileCompilationResult.Failure(
                listOf(
                    CompilationError(
                        uri = uri,
                        message = e.message ?: "Unknown error: ${e.javaClass.simpleName}",
                        phase = "SEMANTIC_ANALYSIS",
                    ),
                ),
            )
        }
    }

    /**
     * Builds locator candidates for a source file.
     * These help the compiler locate the source unit during error reporting.
     */
    private fun buildLocatorCandidates(uri: URI, sourcePath: Path): Set<String> {
        val candidates = mutableSetOf<String>()
        candidates += uri.toString()
        candidates += uri.path
        candidates += sourcePath.toString()
        candidates += sourcePath.toAbsolutePath().toString()
        return candidates
    }

    /**
     * Internal result type for individual file compilation.
     */
    private sealed class FileCompilationResult {
        data class Success(val uri: URI, val module: ModuleNode) : FileCompilationResult()
        data class Failure(val errors: List<CompilationError>) : FileCompilationResult()
    }
}
