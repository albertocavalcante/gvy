// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.Deferred
import java.net.URI

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

    // Context-owned work stays separate from background compilations that publish the workspace's global indexes.
    private val compilationJobs = PendingRegistry<CompilationIdentity, CompilationResult>()
    internal val snapshotJobs = PendingRegistry<CapturedCompilation, SnapshotAcquisition>(CapturedCompilation::delivery)
    internal val discoveryJobs = PendingRegistry<CompiledDocumentSnapshot, Result<PreparedDiscovery?>>()

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

    internal fun getForCompile(
        uri: URI,
        content: String,
        requester: CompilationRequestContext,
        phase: Int,
        mode: ParseMode,
    ): CachedParse? = cache.getForCompile(uri, content, requester, phase, mode)

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
    ): Boolean = publishCompile(ticket, CachedParse(content, parseResult, configFingerprint, null), { true }, alongside)

    internal fun publishCompile(
        ticket: ArtifactOwnership.Ticket,
        entry: CachedParse,
        isValid: () -> Boolean,
        alongside: () -> Unit,
    ): Boolean {
        var stored = false
        ownership.commit(ticket) {
            if (isValid()) {
                cache.putForCompile(ticket.uri, entry)
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
    ): PendingCompilation = compilationJobs.register(uri, identity, ticket, identity::matches, create)

    internal fun getOwned(uri: URI) = cache.getOwned(uri)

    internal fun storeSnapshot(snapshot: OwnedSnapshot, previous: OwnedSnapshot?): Boolean {
        if (!snapshot.capture.isCurrent()) return false
        var admitted = false
        ownership.commit(snapshot.capture.ticket) {
            if (snapshot.capture.ticketsCurrent()) admitted = cache.storeSnapshot(snapshot, previous)
        }
        return admitted
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
        detach(uri, allInputsRetired = false).retire()
    }

    /**
     * Drops [uri]'s cached parse and its pending job, returning that job for the caller to [PendingCompilation.retire]
     * once it holds no lock: a waiter can resume inline and must not re-resolve inside a fence's cleanup (#1429).
     * Removed before its waiters wake, so none can select it again.
     */
    internal fun detach(uri: URI, allInputsRetired: Boolean = true): RetiredCompilations {
        cache.invalidate(uri)
        val compile = compilationJobs.detach(uri)
        val snapshots = detachSnapshotJobs(if (allInputsRetired) null else uri)
        return RetiredCompilations {
            compile?.retire()
            snapshots.retire()
        }
    }

    /**
     * Clears all cached data and cancels every compilation job.
     * Used when workspace configuration changes significantly.
     */
    fun clear() = detach().retire()

    /**
     * Drops all cached data and pending work in O(1), returning both detached registries for the caller to
     * retire once it releases the fence. A global clear cancels outside the fence, so neither the
     * jobs' iteration nor their cancellation handlers run while the fence is held (#1342).
     */
    internal fun detach(): RetiredCompilations {
        cache.clear()
        val compile = compilationJobs.detach()
        val snapshots = detachSnapshotJobs()
        return RetiredCompilations {
            compile.retire()
            snapshots.retire()
        }
    }

    /**
     * Gets cache statistics for monitoring and debugging.
     *
     * @return Map containing cache metrics
     */
    fun getStatistics(): Map<String, Any> = buildMap {
        putAll(cache.getStatistics())
        put("activeCompilations", compilationJobs.size + snapshotJobs.size + discoveryJobs.size)
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

/** Test discovery's structural pass follows the same source retirements as its canonical parse. */
internal fun CompilationCacheService.detachSnapshotJobs(uri: URI? = null): RetiredCompilations {
    val snapshots = if (uri == null) snapshotJobs.detach() else snapshotJobs.detach(uri)
    val discoveries = if (uri == null) discoveryJobs.detach() else discoveryJobs.detach(uri)
    return RetiredCompilations {
        snapshots?.retire()
        discoveries?.retire()
    }
}
