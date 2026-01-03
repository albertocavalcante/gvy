package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service for indexing Groovy source files to extract symbols for resolution.
 *
 * This service manages two separate symbol index caches:
 * - symbolStorageCache: LRU cache for recently accessed files (max 100 entries)
 * - workspaceSymbolIndex: Persistent index for all workspace files
 *
 * The service supports both single-file indexing and batch workspace indexing
 * with progress reporting.
 *
 * Thread-safe: Uses LRUCache with ReentrantReadWriteLock and ConcurrentHashMap.
 *
 * @param ioDispatcher Coroutine dispatcher for IO-bound operations
 * @param workerSessionManager Manager for parser worker sessions
 * @param workspaceManager Workspace configuration and classpath provider
 * @param maxCacheSize Maximum size of the LRU cache (default: 100)
 */
class SymbolIndexingService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val maxCacheSize: Int = 100,
) {
    companion object {
        /**
         * Batch size for parallel workspace indexing.
         * Balances parallelism with resource usage.
         */
        private const val INDEXING_BATCH_SIZE = 10
    }

    private val logger = LoggerFactory.getLogger(SymbolIndexingService::class.java)
    private val symbolStorageCache = LRUCache<URI, SymbolIndex>(maxSize = maxCacheSize)
    private val workspaceSymbolIndex = ConcurrentHashMap<URI, SymbolIndex>()

    /**
     * Gets the symbol index for a single file.
     * Checks cache first, builds from AST model if needed.
     *
     * @param uri The URI of the file
     * @param astModelProvider Optional provider for AST model if file is already parsed
     * @return SymbolIndex if available, null otherwise
     */
    fun getSymbolIndex(
        uri: URI,
        astModelProvider: (() -> com.github.albertocavalcante.groovyparser.ast.GroovyAstModel?)? = null,
    ): SymbolIndex? {
        // Check cache first
        symbolStorageCache.get(uri)?.let { return it }

        // Build from AST model if provided
        val astModel = astModelProvider?.invoke() ?: return null
        val storage = SymbolIndex().buildFromVisitor(astModel)
        symbolStorageCache.put(uri, storage)
        return storage
    }

    /**
     * Gets all symbol indices from both caches.
     * Workspace index entries take precedence over LRU cache in case of conflicts.
     *
     * @return Map of URI to SymbolIndex for all indexed files
     */
    fun getAllSymbolIndices(): Map<URI, SymbolIndex> {
        val cacheSnapshot = symbolStorageCache.snapshot()
        val workspaceSnapshot = workspaceSymbolIndex.entries.associate { (uri, index) -> uri to index }
        val allStorages = cacheSnapshot.toMutableMap()

        workspaceSnapshot.forEach { (uri, index) ->
            val existing = allStorages[uri]
            if (existing != null && existing !== index) {
                logger.warn(
                    "Duplicate SymbolIndex for {} found in cache and workspace index. Using workspace index value.",
                    uri,
                )
            }
            allStorages[uri] = index
        }

        return allStorages
    }

    /**
     * Indexes a single workspace file for symbol resolution.
     * Lightweight operation that parses and builds SymbolIndex without full compilation.
     *
     * @param uri The URI of the file to index
     * @return SymbolIndex if indexing succeeded, null otherwise
     */
    suspend fun indexFile(uri: URI): SymbolIndex? {
        // Check if already indexed first
        symbolStorageCache.get(uri)?.let {
            logger.debug("File already indexed: $uri")
            return it
        }

        val path = parseUriToPath(uri) ?: return null

        return when {
            !Files.exists(path) || !Files.isRegularFile(path) -> {
                logger.debug("File does not exist or is not a regular file: $uri")
                null
            }

            else -> performIndexing(uri, path)
        }
    }

    /**
     * Indexes all workspace source files in the background.
     * Reports progress via callback function.
     *
     * @param uris List of URIs to index
     * @param onProgress Callback invoked with (indexed, total) progress
     */
    suspend fun indexAllWorkspaceSources(uris: List<URI>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        if (uris.isEmpty()) {
            logger.debug("No workspace sources to index")
            return
        }

        logger.info("Starting workspace indexing: ${uris.size} files")
        val total = uris.size
        val indexed = AtomicInteger(0)

        // Index files in parallel batches
        // NOTE: Batch size balances parallelism with resource usage
        // Uses ioDispatcher since call chain includes blocking I/O (Files.readString)
        uris.chunked(INDEXING_BATCH_SIZE).forEach { batch ->
            coroutineScope {
                batch.forEach { uri ->
                    @Suppress("kotlin:S6311") // NOSONAR - IO dispatcher required for blocking file operations
                    launch(ioDispatcher) {
                        indexFileWithProgress(uri, indexed, total, onProgress)
                    }
                }
            }
        }

        logger.info("Workspace indexing complete: ${indexed.get()}/$total files indexed")
    }

    /**
     * Invalidates cached symbol index for a specific URI.
     * Removes from both LRU cache and workspace index.
     *
     * @param uri The URI to invalidate
     */
    fun invalidate(uri: URI) {
        symbolStorageCache.remove(uri)
        workspaceSymbolIndex.remove(uri)
    }

    /**
     * Clears all symbol indices.
     * Used when workspace configuration changes significantly.
     */
    fun clear() {
        symbolStorageCache.clear()
        workspaceSymbolIndex.clear()
    }

    // Private helper methods

    private fun parseUriToPath(uri: URI): Path? = try {
        Path.of(uri)
    } catch (e: InvalidPathException) {
        logger.debug("Failed to convert URI to path: $uri", e)
        null
    }

    /**
     * Performs the actual indexing of a file.
     * Catches all exceptions to prevent individual file failures from stopping batch indexing.
     */
    @Suppress("TooGenericExceptionCaught") // NOTE: Various exceptions possible (IOException, ParseException, etc.)
    private suspend fun performIndexing(uri: URI, path: Path): SymbolIndex? = try {
        val content = Files.readString(path)
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Parse the source code for indexing
        // Note: GroovyParserFacade automatically retries at CONVERSION phase if it detects
        // Script fallback (when a class extends groovy.lang.Script due to unresolved superclass)
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = workspaceManager.getDependencyClasspath(),
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = emptyList(), // Don't recurse during indexing
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
            ),
        )

        val astModel = parseResult.astModel
        val index = SymbolIndex().buildFromVisitor(astModel)
        symbolStorageCache.put(uri, index)
        workspaceSymbolIndex[uri] = index
        logger.debug("Indexed workspace file: $uri")
        index
    } catch (e: Exception) {
        logger.warn("Failed to index workspace file: $uri", e)
        null
    }

    /**
     * Indexes a single file and reports progress atomically.
     */
    @Suppress("TooGenericExceptionCaught") // NOTE: Various exceptions possible (IOException, ParseException, etc.)
    private suspend fun indexFileWithProgress(
        uri: URI,
        indexed: AtomicInteger,
        total: Int,
        onProgress: (Int, Int) -> Unit,
    ) {
        try {
            indexFile(uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            // Catch all to prevent batch failure from stopping entire indexing
            logger.warn("Failed to index file: $uri", e)
        } finally {
            val currentCount = indexed.incrementAndGet()
            onProgress(currentCount, total)
        }
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
