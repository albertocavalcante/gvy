package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service for indexing Groovy source files to extract symbols for resolution.
 *
 * Holds one [SymbolIndex] per file, stamped with a hash of the content it was built from. A caller that
 * has the current content ([ensureIndexed], [indexFile]) therefore never gets an index of older content,
 * and every compile - including a compile cache hit - can restore an index that is missing.
 *
 * The map is deliberately unbounded: it is the workspace-wide symbol index, so evicting a file would
 * silently drop it from workspace symbols, completion and navigation. Entries leave through [invalidate],
 * [clear], or when [indexFile] finds the file gone.
 *
 * Thread-safe: backed by a ConcurrentHashMap.
 *
 * @param ioDispatcher Coroutine dispatcher for IO-bound operations
 * @param workerSessionManager Manager for parser worker sessions
 * @param workspaceManager Workspace configuration and classpath provider
 */
class SymbolIndexingService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    /** Whether [uri] is open in the editor: its buffer, indexed by the compile path, wins over the disk. */
    private val isOpen: (URI) -> Boolean = { false },
) {
    companion object {
        /**
         * Batch size for parallel workspace indexing.
         * Balances parallelism with resource usage.
         */
        private const val INDEXING_BATCH_SIZE = 10
    }

    /** An index together with the hash of the content it was built from. */
    private class Entry(val contentHash: String, val index: SymbolIndex)

    private val logger = KotlinLogging.logger {}
    private val entries = ConcurrentHashMap<URI, Entry>()

    /** Gets the stored symbol index for a file, or null if none has been built. */
    fun getSymbolIndex(uri: URI): SymbolIndex? = entries[uri]?.index

    /**
     * Builds and stores the index for [content], replacing whatever was there.
     * Used when a fresh compile result is at hand.
     */
    fun indexCompiled(uri: URI, content: String, astModel: GroovyAstModel): SymbolIndex =
        store(uri, hash(content), astModel)

    /**
     * Returns the index for [content], building it from [astModelProvider] only when the stored one was built
     * from different content (or is missing). Cheap on the compile-cache-hit path.
     */
    fun ensureIndexed(uri: URI, content: String, astModelProvider: () -> GroovyAstModel?): SymbolIndex? {
        val contentHash = hash(content)
        entries[uri]?.takeIf { it.contentHash == contentHash }?.let { return it.index }
        val astModel = astModelProvider() ?: return null
        return store(uri, contentHash, astModel)
    }

    /**
     * Gets all symbol indices, in deterministic order (sorted by URI).
     *
     * @return Map of URI to SymbolIndex for all indexed files
     */
    fun getAllSymbolIndices(): Map<URI, SymbolIndex> = entries.entries.sortedBy { it.key }
        .associateTo(LinkedHashMap()) { it.key to it.value.index }

    /**
     * Indexes a single workspace file for symbol resolution.
     * Lightweight operation that parses and builds SymbolIndex without full compilation.
     * Re-indexes when the file's content changed since it was last indexed, and drops the entry
     * when the file no longer exists.
     *
     * @param uri The URI of the file to index
     * @return SymbolIndex if indexing succeeded, null otherwise
     */
    suspend fun indexFile(uri: URI): SymbolIndex? {
        // An open file is indexed from its buffer when it compiles; a save or watcher event must not
        // replace that with the disk copy the user is not looking at.
        if (isOpen(uri)) return entries[uri]?.index
        val path = parseUriToPath(uri) ?: return null

        if (!Files.isRegularFile(path)) {
            logger.debug { "File does not exist or is not a regular file: $uri" }
            entries.remove(uri)
            return null
        }

        return performIndexing(uri, path)
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
            logger.debug { "No workspace sources to index" }
            return
        }

        logger.info { "Starting workspace indexing: ${uris.size} files" }
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

        logger.info { "Workspace indexing complete: ${indexed.get()}/$total files indexed" }
    }

    /**
     * Invalidates the stored symbol index for a specific URI.
     *
     * @param uri The URI to invalidate
     */
    fun invalidate(uri: URI) {
        entries.remove(uri)
    }

    /**
     * Clears all symbol indices.
     * Used when workspace configuration changes significantly.
     */

    /**
     * After [uri] is closed its disk copy is what the workspace holds again: re-index from disk (a no-op
     * when the buffer matched it), or drop the entry of a buffer that has no file (`untitled:`).
     */
    suspend fun refreshAfterClose(uri: URI) {
        if (parseUriToPath(uri) == null) entries.remove(uri) else indexFile(uri)
    }

    fun clear() {
        entries.clear()
    }

    // Private helper methods

    private fun parseUriToPath(uri: URI): Path? = UriPathConverter.toPath(uri)

    private fun store(uri: URI, contentHash: String, astModel: GroovyAstModel): SymbolIndex {
        val index = SymbolIndex().buildFromVisitor(astModel)
        entries[uri] = Entry(contentHash, index)
        return index
    }

    private fun hash(content: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()),
    )

    /**
     * Performs the actual indexing of a file.
     * Catches all exceptions to prevent individual file failures from stopping batch indexing.
     */
    @Suppress("TooGenericExceptionCaught") // NOTE: Various exceptions possible (IOException, ParseException, etc.)
    private suspend fun performIndexing(uri: URI, path: Path): SymbolIndex? = try {
        val content = Files.readString(path)
        val contentHash = hash(content)
        entries[uri]?.takeIf { it.contentHash == contentHash }?.let {
            logger.debug { "File already indexed: $uri" }
            return it.index
        }

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
                locatorCandidates = buildLocatorCandidates(uri, path),
            ),
        )

        // Opened while it was parsed: the compile path has indexed the buffer, which wins over this disk copy.
        if (isOpen(uri)) {
            entries[uri]?.index
        } else {
            store(uri, contentHash, parseResult.astModel).also { logger.debug { "Indexed workspace file: $uri" } }
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to index workspace file: $uri" }
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
            logger.warn(e) { "Failed to index file: $uri" }
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
