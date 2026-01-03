package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.codehaus.groovy.control.Phases
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path

private const val RETRY_DELAY_MS = 50L

/**
 * Orchestrates the Groovy compilation process, handling caching,
 * async coordination, and delegation to the worker sessions.
 */
class CompilationOrchestrator(
    private val cacheService: CompilationCacheService,
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val symbolIndexer: SymbolIndexingService,
    private val parseAccessor: ParseResultAccessor,
    private val resultMapper: CompilationResultMapper,
    private val ioDispatcher: CoroutineDispatcher,
    private val errorHandler: CompilationErrorHandler,
) {
    private val logger = LoggerFactory.getLogger(CompilationOrchestrator::class.java)

    /**
     * Compiles Groovy source code and returns the result.
     */
    suspend fun compile(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): CompilationResult {
        logger.debug("Compiling: $uri (phase=$compilePhase)")

        return try {
            // Check cache first
            val cachedResult = cacheService.getCached(uri, content)
            if (cachedResult != null) {
                // Check for suspicious Script node
                val isSuspiciousScriptNode = parseAccessor.isSuspiciousScript(uri, cachedResult)

                if (isSuspiciousScriptNode) {
                    logger.info("Cached result has suspicious Script node for $uri, re-compiling")
                    performCompilation(uri, content, compilePhase)
                } else {
                    logger.debug("Using cached parse result for: $uri")
                    resultMapper.map(cachedResult, content)
                }
            } else {
                performCompilation(uri, content, compilePhase)
            }
        } catch (e: Exception) {
            errorHandler.handleException(e, uri)
        }
    }

    private suspend fun performCompilation(
        uri: URI,
        content: String,
        compilePhase: Int = Phases.CANONICALIZATION,
    ): CompilationResult {
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Get file-specific classpath
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Parse the source code
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
            ),
        )

        val ast = parseResult.ast

        if (ast != null) {
            // Cache parse result
            cacheService.putCached(uri, content, parseResult)

            // Index symbols
            symbolIndexer.getSymbolIndex(uri) { parseResult.astModel }
        }

        return resultMapper.map(parseResult, content)
    }

    /**
     * Compile a transient version without updating cache.
     */
    suspend fun compileTransient(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): ParseResult {
        logger.debug("Transient compile: $uri")
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        return workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
            ),
        )
    }

    /**
     * Start async compilation and return Deferred for coordination.
     */
    fun compileAsync(scope: CoroutineScope, uri: URI, content: String): Deferred<CompilationResult> {
        // Check if already compiling this document
        cacheService.getActiveCompilation(uri)?.let { existing ->
            if (existing.isActive) {
                logger.debug("Reusing active compilation for: $uri")
                return existing
            }
        }

        val deferred = scope.async(ioDispatcher) {
            try {
                compile(uri, content)
            } finally {
                cacheService.removeCompilation(uri)
            }
        }

        cacheService.trackCompilation(uri, deferred)
        return deferred
    }

    /**
     * Ensures a file is compiled, either by awaiting active compilation or fetching from cache.
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        // Check for active compilation first
        cacheService.getActiveCompilation(uri)?.let { deferred ->
            if (deferred.isActive) {
                logger.debug("Awaiting active compilation for: $uri")
                return try {
                    deferred.await()
                } catch (e: CancellationException) {
                    // If compilation was cancelled, try once more
                    logger.debug("Compilation cancelled for $uri, retrying...")
                    delay(RETRY_DELAY_MS)
                    cacheService.getActiveCompilation(uri)?.await()
                }
            }
        }

        // Check cache
        cacheService.getCachedWithContent(uri)?.let { (content, parseResult) ->
            logger.debug("Using cached result for: $uri")
            return resultMapper.mapFromCache(parseResult, content)
        }

        logger.debug("No compilation found for $uri (not cached, not compiling)")
        return null
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
}
