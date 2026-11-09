package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.extension
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
 * Files compile in parallel on Dispatchers.Default, and every successful compile feeds the shared
 * [SemanticIndexer], so cross-file references cover files that were never opened.
 *
 * @param workerSessionManager The worker session manager for executing compilation
 * @param workspaceManager The workspace manager providing workspace sources and configuration
 * @param semanticIndexer The shared owner of the workspace semantic index
 * @param ownership Publication fence shared with the other artifacts of the same files
 */
class WorkspaceCompiler(
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val semanticIndexer: SemanticIndexer,
    val dependencyGraph: DependencyGraph = DependencyGraph(),
    private val ownership: ArtifactOwnership = ArtifactOwnership(),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Cached compilation results. Maps URI to compiled ModuleNode.
     * This cache is updated after each workspace compilation.
     */
    private val moduleCache = mutableMapOf<URI, ModuleNode>()

    // Set once the eager workspace compile has run: from then on every workspace source has a document.
    @Volatile
    private var workspaceCompiled = false

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
        logger.info { "Starting workspace compilation" }

        // Get all workspace sources
        val workspaceSources = workspaceManager.getWorkspaceSources()
        logger.info { "Found ${workspaceSources.size} workspace sources to compile" }

        if (workspaceSources.isEmpty()) {
            logger.info { "No workspace sources found, returning empty result" }
            return@withContext WorkspaceCompilationResult(
                modules = emptyMap(),
                errors = emptyList(),
                success = true,
            )
        }

        // Compile all files together
        val result = compileFiles(workspaceSources)
        workspaceCompiled = true

        val elapsed = System.currentTimeMillis() - startTime
        logger.info {
            "Workspace compilation completed in ${elapsed}ms: " +
                "${result.modules.size} modules, ${result.errors.size} errors"
        }

        result
    }

    /**
     * Re-derives the semantic documents of [uris] from the files on disk (after a watcher event, or once
     * the editor buffer a file followed is closed).
     *
     * A file that is gone, unreadable, not a `.groovy` source, or never existed (`untitled:`) loses its
     * document. An open file keeps following its buffer, and one whose document already matches the disk
     * is skipped. Each compile includes every workspace source, so many changed files (a branch switch)
     * get one workspace compile instead of one each. Without the eager workspace compile (the workspace is
     * over the configured size limit) only files that already have a document are refreshed.
     */
    suspend fun refreshFromDisk(uris: Collection<URI>) = withContext(Dispatchers.IO) {
        val stale = uris.filter(::needsRefresh)
        when {
            stale.isEmpty() -> Unit
            workspaceCompiled && stale.size > BATCH_REFRESH_LIMIT -> compileWorkspace()
            else -> stale.filter { workspaceCompiled || it in semanticIndexer }.forEach { uri ->
                compileFileWithWorkspaceContext(
                    checkNotNull(UriPathConverter.toPath(uri)),
                    workspaceManager.getWorkspaceSources(),
                    workspaceManager.getDependencyClasspath(),
                    workspaceManager.getSourceRoots(),
                )
            }
        }
    }

    /** Whether [uri]'s document must be rebuilt from disk; drops it when there is no source file to build from. */
    private fun needsRefresh(uri: URI): Boolean {
        // Owned before the open check: a reopen in between makes this removal stale (#1184).
        val ticket = ownership.begin(uri)
        if (semanticIndexer.followsBuffer(uri)) return false
        val path = UriPathConverter.toPath(uri)?.takeIf { it.extension.equals("groovy", ignoreCase = true) }
        // Read once: the file may vanish between a check and the read (a checkout in progress).
        val content = path?.let { runCatching { it.readText() }.getOrNull() }
        if (content == null) {
            ownership.commit(ticket) { if (!semanticIndexer.followsBuffer(uri)) semanticIndexer.remove(uri) }
        }
        return content != null && !semanticIndexer.isCurrent(uri, content)
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
        val publication = semanticIndexer.begin(uri)

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
                logger.debug { "Successfully compiled: $uri" }
                semanticIndexer.publish(
                    publication,
                    content,
                    module,
                    parseResult.outcome.achievedPhase,
                    fromBuffer = false,
                )
                FileCompilationResult.Success(uri, module)
            } else {
                val errorMessage = if (parseResult.diagnostics.isNotEmpty()) {
                    parseResult.diagnostics.joinToString("; ") { it.message }
                } else {
                    "No AST produced"
                }
                logger.warn { "Failed to compile $uri: $errorMessage" }
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
            logger.error(e) { "Exception compiling $uri" }
            FileCompilationResult.Failure(
                listOf(
                    CompilationError(
                        uri = uri,
                        message = e.message ?: "Unknown error: ${e.javaClass.simpleName}",
                        phase = "SEMANTIC_ANALYSIS",
                    ),
                ),
            )
        } finally {
            publication.close()
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

    private companion object {
        /** Changed files beyond which one workspace compile is cheaper than compiling each against it. */
        const val BATCH_REFRESH_LIMIT = 8
    }
}
