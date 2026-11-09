package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.Deferred
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing compilation result caching and async compilation job tracking.
 *
 * This service wraps the CompilationCache and provides additional functionality
 * for tracking ongoing compilation jobs to enable async coordination and job reuse.
 *
 * Thread-safe: the bounded CompilationCache is internally synchronized and job tracking uses ConcurrentHashMap.
 * Cached results are LRU-bounded (open documents are never evicted); see [CompilationCache].
 */
class CompilationCacheService(
    maxEntries: Int = CompilationCache.DEFAULT_MAX_ENTRIES,
    isOpen: (URI) -> Boolean = { false },
    /** Fences cache writes against invalidation; share it with every other artifact of the same files. */
    val ownership: ArtifactOwnership = ArtifactOwnership(),
) {
    private val cache = CompilationCache(maxEntries, isOpen)
    private val compilationJobs = ConcurrentHashMap<URI, PendingCompilation>()

    /**
     * Gets cached ParseResult if content AND configuration fingerprint match.
     * Returns null if not cached, content has changed, or configuration has changed.
     * Issue #743: Cache coherency through configuration fingerprinting.
     *
     * @param uri The URI of the file
     * @param content The current content of the file
     * @param configFingerprint The current workspace configuration fingerprint
     * @return ParseResult if cached and both content and fingerprint match, null otherwise
     */
    fun getCached(uri: URI, content: String, configFingerprint: String): ParseResult? =
        cache.get(uri, content, configFingerprint)

    /**
     * Gets the cached content and ParseResult, if they were compiled under [configFingerprint].
     * For readers that do not hold the current text: invalidation keeps such an entry in step with its source.
     *
     * @param uri The URI of the file
     * @param configFingerprint The current workspace configuration fingerprint
     * @return Pair of (content, ParseResult) if cached under that configuration, null otherwise
     */
    fun getCachedWithContent(uri: URI, configFingerprint: String): Pair<String, ParseResult>? =
        cache.getWithContent(uri, configFingerprint)

    /**
     * Stores a ParseResult (and [alongside], the other artifacts of the same compile) only while [ticket] is
     * current, atomically with respect to invalidation. Returns whether it was stored.
     * Issue #743: Cache coherency through configuration fingerprinting.
     *
     * @param ticket Ownership taken before the compile read its inputs
     * @param content The content that was compiled
     * @param parseResult The result of compilation
     * @param configFingerprint The workspace configuration fingerprint at compilation time
     * @param alongside Short, non-suspending writes of artifacts derived from the same compile
     */
    internal fun publish(
        ticket: ArtifactOwnership.Ticket,
        content: String,
        parseResult: ParseResult,
        configFingerprint: String,
        alongside: () -> Unit = {},
    ): Boolean = ownership.commit(ticket) {
        cache.put(ticket.uri, content, parseResult, configFingerprint)
        alongside()
    }

    /**
     * Atomically selects compatible work or registers a new lazy job before it can start.
     * [create] must only construct work; starting it and external lifecycle callbacks belong outside
     * the map operation. Late handler attachment also cleans up an already finished/cancelled job.
     */
    internal fun registerCompilation(
        uri: URI,
        identity: CompilationIdentity,
        create: () -> Deferred<CompilationResult>,
    ): Deferred<CompilationResult> {
        var created = false
        var selected: PendingCompilation? = null
        compilationJobs.compute(uri) { _, existing ->
            if (existing != null && existing.isPending && existing.identity.matches(identity)) {
                selected = existing
                existing
            } else {
                val replacement = PendingCompilation(identity, create())
                selected = replacement
                created = true
                // An already cancelled creator must not evict another caller's live work.
                replacement.takeIf { it.isPending } ?: existing?.takeIf { it.isPending }
            }
        }
        val compilation = checkNotNull(selected)
        if (created) {
            compilation.deferred.invokeOnCompletion { compilationJobs.remove(uri, compilation) }
        }
        return compilation.deferred
    }

    /**
     * Gets an active compilation job if one exists.
     * Returns null if no compilation is in progress for the URI.
     *
     * @param uri The URI to check
     * @return Deferred compilation job if active, null otherwise
     */
    fun getActiveCompilation(uri: URI): Deferred<CompilationResult>? = compilationJobs[uri]?.deferred

    /**
     * Unmanaged callers have no authoritative buffer text. They may await the latest unversioned
     * request with the current configuration, but never adopt an old open-document job after close.
     */
    internal fun getPendingCompilation(uri: URI, configurationFingerprint: String): PendingCompilation? =
        compilationJobs[uri]?.takeIf {
            it.isPending && it.identity.document == null &&
                it.identity.configurationFingerprint == configurationFingerprint
        }

    /**
     * Invalidates all cached data for a specific URI: removes its cached parse result and cancels its
     * ongoing compilation job. Callers retire the URI's [ownership] first, so the job cannot publish anyway;
     * awaiting callers that are still current retry against the new state.
     *
     * @param uri The URI to invalidate
     */
    fun invalidate(uri: URI) {
        cache.invalidate(uri)
        compilationJobs.remove(uri)?.deferred?.cancel()
    }

    /**
     * Clears all cached data and cancels every compilation job.
     * Used when workspace configuration changes significantly.
     */
    fun clear() {
        cache.clear()
        compilationJobs.keys.forEach { uri -> compilationJobs.remove(uri)?.deferred?.cancel() }
    }

    /**
     * Gets cache statistics for monitoring and debugging.
     *
     * @return Map containing cache metrics
     */
    fun getStatistics(): Map<String, Any> = buildMap {
        putAll(cache.getStatistics())
        put("activeCompilations", compilationJobs.size)
    }

    /**
     * Gets all currently cached URIs.
     *
     * @return Set of URIs that have cached results
     */
    fun getCachedUris(): Set<URI> = cache.keys()

    /** Changes the maximum number of cached results (open documents are exempt from eviction). */
    fun resize(maxEntries: Int) = cache.resize(maxEntries)
}
