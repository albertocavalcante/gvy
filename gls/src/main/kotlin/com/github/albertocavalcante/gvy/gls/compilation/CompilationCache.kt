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
    // Every access holds [lock] (not the map itself, which [clear] replaces), so a swap is never observed midway
    private val lock = Any()
    private var entries = newEntries()
    private var evictions = 0L

    /**
     * Gets cached result if content AND configuration fingerprint match.
     * Returns null if not cached or if either content or fingerprint has changed.
     */
    fun get(uri: URI, content: String, configFingerprint: String): ParseResult? = synchronized(lock) {
        val entry = entries[uri] ?: return null
        if (entry.content == content && entry.configFingerprint == configFingerprint) entry.parseResult else null
    }

    /**
     * The entry for [uri] with the source it was compiled from, if it was compiled under [configFingerprint].
     * For readers without the current text; the entry itself must be retired when that text changes.
     */
    fun getWithContent(uri: URI, configFingerprint: String): Pair<String, ParseResult>? = synchronized(lock) {
        entries[uri]?.takeIf { it.configFingerprint == configFingerprint }?.let { it.content to it.parseResult }
    }

    /**
     * Stores a parse result with its content and configuration fingerprint.
     */
    fun put(uri: URI, content: String, parseResult: ParseResult, configFingerprint: String) {
        store(uri, CacheEntry(content, parseResult, configFingerprint))
    }

    fun invalidate(uri: URI) {
        synchronized(lock) { entries.remove(uri) }
    }

    fun clear() {
        // O(1): the old map is left to the garbage collector (#1342)
        synchronized(lock) { entries = newEntries() }
    }

    /** Snapshot of the cached URIs, least recently used first. */
    fun keys(): Set<URI> = synchronized(lock) { LinkedHashSet(entries.keys) }

    /** Changes the cap and evicts immediately if the cache is now over it. */
    fun resize(newMaxEntries: Int) {
        synchronized(lock) {
            maxEntries = newMaxEntries.coerceAtLeast(1)
            evictOverflow(keep = null)
        }
    }

    fun getStatistics(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "cachedResults" to entries.size,
            "maxEntries" to maxEntries,
            "evictions" to evictions,
        )
    }

    private fun store(uri: URI, entry: CacheEntry) {
        synchronized(lock) {
            entries[uri] = entry
            // Never evict what was just stored: callers read it back right away (e.g. go-to-definition
            // compiles a closed file, then fetches its AST), and when open documents fill the cap the
            // new entry would otherwise be the only candidate.
            evictOverflow(keep = uri)
        }
    }

    /**
     * Must be called holding the monitor. Evicts closed entries other than [keep], least recently used
     * first. The cap is soft: open documents and [keep] are never evicted, even if they exceed it.
     */
    private fun evictOverflow(keep: URI?) {
        if (entries.size <= maxEntries) return
        val candidates = entries.keys.iterator()
        while (entries.size > maxEntries && candidates.hasNext()) {
            val uri = candidates.next()
            if (uri != keep && !isOpen(uri)) {
                candidates.remove()
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

    private fun newEntries() = LinkedHashMap<URI, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
}
