package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseResult
import java.net.URI

/**
 * Cache entry storing content, parse result, and configuration fingerprint.
 * The fingerprint ensures cache coherency when workspace configuration changes (Issue #743).
 */
private data class CacheEntry(val content: String, val parseResult: ParseResult, val configFingerprint: String)

/**
 * In-memory cache of compilation results, bounded by an LRU policy.
 *
 * Every cached [ParseResult] holds a whole AST, so an unbounded map grows with every file ever
 * compiled (including files compiled from disk for navigation). Once [maxEntries] is exceeded the
 * least recently used entries are evicted, except documents for which [isOpen] returns true: those
 * are needed by every request and are never evicted. [isOpen] is consulted at eviction time, so the
 * cache needs no didOpen/didClose lifecycle hooks and cannot leak a pin. An evicted file is simply
 * recompiled on next use.
 *
 * The cap is a soft limit when more than [maxEntries] documents are open: only closed entries are
 * ever evicted.
 *
 * Thread-safe: all access goes through a single monitor. Operations are O(1) except an over-cap
 * [put], which scans from the LRU end past open entries to find victims.
 */
class CompilationCache(maxEntries: Int = DEFAULT_MAX_ENTRIES, private val isOpen: (URI) -> Boolean = { false }) {
    @Volatile
    var maxEntries: Int = maxEntries.coerceAtLeast(1)
        private set

    // access-ordered: iteration runs from least to most recently used
    private val entries = LinkedHashMap<URI, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var evictions = 0L

    /**
     * Gets cached result if content AND configuration fingerprint match.
     * Returns null if not cached or if either content or fingerprint has changed.
     */
    fun get(uri: URI, content: String, configFingerprint: String): ParseResult? = synchronized(entries) {
        val entry = entries[uri] ?: return null
        if (entry.content == content && entry.configFingerprint == configFingerprint) entry.parseResult else null
    }

    /**
     * Gets cached result if content matches (ignores fingerprint).
     * For backward compatibility with callers that don't have fingerprint context.
     */
    fun get(uri: URI, content: String): ParseResult? = synchronized(entries) {
        val entry = entries[uri] ?: return null
        if (entry.content == content) entry.parseResult else null
    }

    fun get(uri: URI): ParseResult? = synchronized(entries) { entries[uri]?.parseResult }

    fun getWithContent(uri: URI): Pair<String, ParseResult>? = synchronized(entries) {
        entries[uri]?.let { it.content to it.parseResult }
    }

    /**
     * Stores a parse result with its content and configuration fingerprint.
     */
    fun put(uri: URI, content: String, parseResult: ParseResult, configFingerprint: String) {
        store(uri, CacheEntry(content, parseResult, configFingerprint))
    }

    /**
     * Stores a parse result without fingerprint (backward compatibility).
     * Uses empty fingerprint. Entries stored this way can be retrieved via get(uri, content)
     * which ignores fingerprint, but will NOT match get(uri, content, fingerprint) unless
     * the fingerprint parameter is also empty.
     */
    fun put(uri: URI, content: String, parseResult: ParseResult) {
        store(uri, CacheEntry(content, parseResult, ""))
    }

    fun invalidate(uri: URI) {
        synchronized(entries) { entries.remove(uri) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** Snapshot of the cached URIs, least recently used first. */
    fun keys(): Set<URI> = synchronized(entries) { LinkedHashSet(entries.keys) }

    /** Changes the cap and evicts immediately if the cache is now over it. */
    fun resize(newMaxEntries: Int) {
        synchronized(entries) {
            maxEntries = newMaxEntries.coerceAtLeast(1)
            evictOverflow()
        }
    }

    fun getStatistics(): Map<String, Any> = synchronized(entries) {
        mapOf(
            "cachedResults" to entries.size,
            "maxEntries" to maxEntries,
            "evictions" to evictions,
        )
    }

    private fun store(uri: URI, entry: CacheEntry) {
        synchronized(entries) {
            entries[uri] = entry
            evictOverflow()
        }
    }

    /** Must be called holding the monitor. Evicts closed entries, least recently used first. */
    private fun evictOverflow() {
        if (entries.size <= maxEntries) return
        val it = entries.keys.iterator()
        while (entries.size > maxEntries && it.hasNext()) {
            if (!isOpen(it.next())) {
                it.remove()
                evictions++
            }
        }
    }

    companion object {
        /** Default cap: a working set of open files plus recently navigated ones, not the workspace. */
        const val DEFAULT_MAX_ENTRIES = 300
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
