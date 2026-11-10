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

    /*
     * Swapped by [detach] (#1342). A registration racing a swap may land in the old map: such a job was claimed
     * before the global retirement, so its old-epoch ticket can never commit, and it is at most wasted work. Each
     * job's own cleanup re-reads this field and removes only itself, so it never touches a newer job.
     */
    @Volatile private var compilationJobs = ConcurrentHashMap<URI, PendingCompilation>()

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
     * @param isValid What else must still hold, checked under the same fence (e.g. the buffer is still open)
     * @param alongside Short, non-suspending writes of artifacts derived from the same compile
     */
    @Suppress("LongParameterList") // one atomic write: what was compiled, under which ticket, and its guards
    internal fun publish(
        ticket: ArtifactOwnership.Ticket,
        content: String,
        parseResult: ParseResult,
        configFingerprint: String,
        isValid: () -> Boolean = { true },
        alongside: () -> Unit = {},
    ): Boolean {
        var stored = false
        ownership.commit(ticket) {
            if (isValid()) {
                cache.put(ticket.uri, content, parseResult, configFingerprint)
                alongside()
                stored = true
            }
        }
        return stored
    }

    /**
     * Atomically selects compatible work or registers a new lazy job before it can start.
     * [create] must only construct work; starting it and external lifecycle callbacks belong outside
     * the map operation. Late handler attachment also cleans up an already finished/cancelled job.
     */
    internal fun registerPending(
        uri: URI,
        identity: CompilationIdentity,
        /** The fence ticket a newly created job runs under; a selected existing job keeps its own. */
        ticket: ArtifactOwnership.Ticket,
        create: () -> Deferred<CompilationResult>,
    ): PendingCompilation {
        var created = false
        var selected: PendingCompilation? = null
        compilationJobs.compute(uri) { _, existing ->
            if (existing != null && existing.isPending && existing.identity.matches(identity)) {
                selected = existing
                existing
            } else {
                val replacement = PendingCompilation(identity, create(), ticket)
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
        return compilation
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
    fun clear() = detach().forEach { it.cancel() }

    /**
     * Drops all cached data and the pending jobs in O(1), returning those jobs for the caller to cancel. A global
     * clear calls this inside the fence and cancels the jobs after it, so their cancellation handlers never run
     * while the fence is held (#1342).
     */
    internal fun detach(): Collection<Deferred<CompilationResult>> {
        cache.clear()
        val detached = compilationJobs
        compilationJobs = ConcurrentHashMap()
        return detached.values.map { it.deferred }
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
