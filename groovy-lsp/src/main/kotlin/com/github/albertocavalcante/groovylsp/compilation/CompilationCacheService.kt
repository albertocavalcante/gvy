package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing compilation result caching and async compilation job tracking.
 *
 * This service wraps the CompilationCache and provides additional functionality
 * for tracking ongoing compilation jobs to enable async coordination and job reuse.
 *
 * Thread-safe: Uses ConcurrentHashMap for all state management.
 */
class CompilationCacheService {
    private val cache = CompilationCache()
    private val compilationJobs = ConcurrentHashMap<URI, Deferred<CompilationResult>>()

    /**
     * Gets cached ParseResult if content matches.
     * Returns null if not cached or content has changed.
     *
     * @param uri The URI of the file
     * @param content The current content of the file
     * @return ParseResult if cached and content matches, null otherwise
     */
    fun getCached(uri: URI, content: String): ParseResult? = cache.get(uri, content)

    /**
     * Gets cached ParseResult without content validation.
     * Returns null if not cached.
     *
     * @param uri The URI of the file
     * @return ParseResult if cached, null otherwise
     */
    fun getCached(uri: URI): ParseResult? = cache.get(uri)

    /**
     * Gets cached content and ParseResult pair.
     * Returns null if not cached.
     *
     * @param uri The URI of the file
     * @return Pair of (content, ParseResult) if cached, null otherwise
     */
    fun getCachedWithContent(uri: URI): Pair<String, ParseResult>? = cache.getWithContent(uri)

    /**
     * Stores a ParseResult in the cache.
     *
     * @param uri The URI of the file
     * @param content The content that was compiled
     * @param parseResult The result of compilation
     */
    fun putCached(uri: URI, content: String, parseResult: ParseResult) {
        cache.put(uri, content, parseResult)
    }

    /**
     * Tracks an ongoing compilation job.
     * Used for async compilation coordination and job reuse.
     *
     * @param uri The URI being compiled
     * @param deferred The deferred compilation job
     */
    fun trackCompilation(uri: URI, deferred: Deferred<CompilationResult>) {
        compilationJobs[uri] = deferred
    }

    /**
     * Gets an active compilation job if one exists.
     * Returns null if no compilation is in progress for the URI.
     *
     * @param uri The URI to check
     * @return Deferred compilation job if active, null otherwise
     */
    fun getActiveCompilation(uri: URI): Deferred<CompilationResult>? = compilationJobs[uri]

    /**
     * Removes a compilation job from tracking.
     * Should be called when compilation completes or is cancelled.
     *
     * @param uri The URI to remove
     */
    fun removeCompilation(uri: URI) {
        compilationJobs.remove(uri)
    }

    /**
     * Invalidates all cached data for a specific URI.
     * Removes both cached parse results and ongoing compilation jobs.
     *
     * @param uri The URI to invalidate
     */
    fun invalidate(uri: URI) {
        cache.invalidate(uri)
        compilationJobs.remove(uri)
    }

    /**
     * Clears all cached data and compilation jobs.
     * Used when workspace configuration changes significantly.
     */
    fun clear() {
        cache.clear()
        compilationJobs.clear()
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
}
