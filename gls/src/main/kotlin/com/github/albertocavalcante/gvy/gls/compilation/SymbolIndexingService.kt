package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
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
 * Every write and removal is committed under [ownership] with a ticket taken before its source was read, so
 * work retired by invalidation (or by the file being reopened or closed) cannot replace or erase newer facts.
 *
 * Thread-safe: backed by a ConcurrentHashMap; writes are serialized with invalidation by [ownership].
 *
 * @param ioDispatcher Coroutine dispatcher for IO-bound operations
 * @param workerSessionManager Manager for parser worker sessions
 * @param workspaceManager Workspace configuration and classpath provider
 * @param ownership Publication fence shared with the compilation cache of the same files
 */
class SymbolIndexingService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val ownership: ArtifactOwnership = ArtifactOwnership(),
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

    /** An index together with the hash of the content it was built from, built before it is committed. */
    internal class Prepared(val contentHash: String, val index: SymbolIndex)

    private val logger = KotlinLogging.logger {}
    private val entries = ConcurrentHashMap<URI, Prepared>()

    /** Gets the stored symbol index for a file, or null if none has been built. */
    fun getSymbolIndex(uri: URI): SymbolIndex? = entries[uri]?.index

    /** Builds the index of [astModel], compiled from [content], without storing it: see [install]. */
    internal fun prepare(content: String, astModel: GroovyAstModel): Prepared = build(contentHash(content), astModel)

    /** Stores [prepared] for [uri]. Only call inside an [ArtifactOwnership.commit] of a current ticket. */
    internal fun install(uri: URI, prepared: Prepared) {
        entries[uri] = prepared
    }

    /** Whether the stored index of [uri] was built from exactly [snapshot]'s text; older text is not current. */
    fun isIndexed(uri: URI, snapshot: DocumentSnapshot): Boolean = entries[uri]?.contentHash == snapshot.contentHash

    /**
     * Returns the index for [content], building it from [astModelProvider] only when the stored one was built
     * from different content (or is missing). Cheap on the compile-cache-hit path. Stores nothing, and returns
     * null, once [ticket] was retired.
     */
    internal fun ensureIndexed(
        ticket: ArtifactOwnership.Ticket,
        content: String,
        astModelProvider: () -> GroovyAstModel?,
    ): SymbolIndex? {
        val hash = contentHash(content)
        entries[ticket.uri]?.takeIf { it.contentHash == hash }?.let { return it.index }
        val astModel = astModelProvider() ?: return null
        val prepared = build(hash, astModel)
        return prepared.index.takeIf { ownership.commit(ticket) { install(ticket.uri, prepared) } }
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
    suspend fun indexFile(uri: URI): SymbolIndex? = indexFile(uri, ownership.begin(uri))

    private suspend fun indexFile(uri: URI, ticket: ArtifactOwnership.Ticket): SymbolIndex? {
        // An open file is indexed from its buffer when it compiles; a save or watcher event must not
        // replace that with the disk copy the user is not looking at.
        if (isOpen(uri)) return entries[uri]?.index
        val path = UriPathConverter.toPath(uri) ?: return null

        if (!Files.isRegularFile(path)) {
            logger.debug { "File does not exist or is not a regular file: $uri" }
            commitFromDisk(ticket) { entries.remove(uri) }
            return null
        }

        return performIndexing(uri, path, ticket)
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
     * After [uri] is closed its disk copy is what the workspace holds again: re-index from disk (a no-op
     * when the buffer matched it), or drop the entry of a buffer that has no file (`untitled:`).
     *
     * [ticket] is taken when the buffer closed: once the document is reopened it is stale, so a delayed
     * refresh cannot erase or replace the facts of the new buffer.
     */
    suspend fun refreshAfterClose(uri: URI, ticket: ArtifactOwnership.Ticket = ownership.begin(uri)) {
        if (UriPathConverter.toPath(uri) == null) {
            commitFromDisk(ticket) { entries.remove(uri) }
        } else {
            indexFile(uri, ticket)
        }
    }

    /**
     * Clears all symbol indices.
     * Used when workspace configuration changes significantly.
     */
    fun clear() {
        entries.clear()
    }

    // Private helper methods

    private fun build(contentHash: String, astModel: GroovyAstModel) =
        Prepared(contentHash, SymbolIndex().buildFromVisitor(astModel))

    /**
     * Applies a disk-derived [write] while [ticket] is current and no buffer is open, checked under the same
     * fence that a reopen advances through: it never replaces or removes an open buffer's facts.
     */
    private fun commitFromDisk(ticket: ArtifactOwnership.Ticket, write: () -> Unit): Boolean {
        var applied = false
        ownership.commit(ticket) {
            if (!isOpen(ticket.uri)) {
                write()
                applied = true
            }
        }
        return applied
    }

    /**
     * Performs the actual indexing of a file.
     * Catches all exceptions to prevent individual file failures from stopping batch indexing.
     */
    @Suppress("TooGenericExceptionCaught") // NOTE: Various exceptions possible (IOException, ParseException, etc.)
    private suspend fun performIndexing(uri: URI, path: Path, ticket: ArtifactOwnership.Ticket): SymbolIndex? = try {
        val content = Files.readString(path)
        val hash = contentHash(content)
        entries[uri]?.takeIf { it.contentHash == hash }?.let {
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

        // Opened (or retired) while it was parsed: the newer facts win over this disk copy.
        val prepared = build(hash, parseResult.astModel)
        if (commitFromDisk(ticket) { install(uri, prepared) }) {
            logger.debug { "Indexed workspace file: $uri" }
            prepared.index
        } else {
            entries[uri]?.index
        }
    } catch (e: CancellationException) {
        throw e
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
        } catch (e: CancellationException) {
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
