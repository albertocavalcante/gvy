// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseResult
import java.net.URI

/**
 * Cache entry storing content, parse result, and configuration fingerprint.
 * The fingerprint ensures cache coherency when workspace configuration changes (Issue #743).
 */
internal data class CachedParse(
    val content: String,
    val parseResult: ParseResult,
    val configFingerprint: String,
    val mode: ParseMode?,
    val owned: OwnedSnapshot? = null,
    val fallback: CapturedCompilation? = null,
    val referenceProducer: ReferenceProducer? = null,
    /** A restored symbol index must retain the original parse window, including its selected target. */
    val symbolInputs: SymbolIndexingService.Inputs? = null,
)

/** Context-owned and legacy parses share a single LRU budget, but only legacy publication writes global indexes. */
private data class CacheKey(val uri: URI, val owned: Boolean = false)

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
        val entry = entries[CacheKey(uri)] ?: return null
        if (entry.content == content && entry.configFingerprint == configFingerprint) entry.parseResult else null
    }

    internal fun getForCompile(
        uri: URI,
        content: String,
        requester: CompilationRequestContext,
        phase: Int,
        mode: ParseMode,
    ): CachedParse? {
        val key = CacheKey(uri)
        val entry = synchronized(lock) { entries[key] } ?: return null
        val outcome = entry.parseResult.outcome
        val matches = entry.content == content &&
            entry.configFingerprint == requester.document.fingerprint &&
            entry.mode == mode &&
            outcome.requestedPhase == phase
        if (!matches) return null
        val fallback = entry.fallback
        return when {
            outcome.achievedPhase >= phase -> entry
            // Input validation can invoke a strategy. Never run it under the LRU monitor, and never return
            // a fallback that was replaced while its original producer's inputs were being checked.
            fallback?.canServe(requester) == true -> synchronized(lock) {
                entries[key]?.takeIf { it === entry }
            }
            else -> null
        }
    }

    internal fun getOwned(uri: URI): OwnedSnapshot? = synchronized(lock) {
        entries[CacheKey(uri, owned = true)]?.owned
    }

    /** A slower producer cannot evict a newer result that arrived since its initial cache lookup. */
    internal fun storeSnapshot(value: OwnedSnapshot, previous: OwnedSnapshot?): Boolean = synchronized(lock) {
        val capture = value.capture
        val key = CacheKey(capture.uri, owned = true)
        if (entries[key]?.owned !== previous) return@synchronized false
        val parsed = value.snapshot.parseResult
        store(key, CachedParse(capture.content, parsed, capture.context.fingerprint, capture.mode, value))
        true
    }

    /**
     * The entry for [uri] with the source it was compiled from, if it was compiled under [configFingerprint].
     * For readers without the current text; the entry itself must be retired when that text changes.
     */
    fun getWithContent(uri: URI, configFingerprint: String): Pair<String, ParseResult>? = synchronized(lock) {
        entries[CacheKey(uri)]?.takeIf { it.configFingerprint == configFingerprint }
            ?.let { it.content to it.parseResult }
    }

    /**
     * Stores a parse result with its content and configuration fingerprint.
     */
    fun put(uri: URI, content: String, parseResult: ParseResult, configFingerprint: String) {
        putForCompile(uri, CachedParse(content, parseResult, configFingerprint, null))
    }

    fun invalidate(uri: URI) {
        synchronized(lock) {
            entries.remove(CacheKey(uri))
            entries.remove(CacheKey(uri, owned = true))
        }
    }

    fun clear() {
        // O(1): the old map is left to the garbage collector (#1342)
        synchronized(lock) { entries = newEntries() }
    }

    /** Snapshot of the cached URIs, least recently used first. */
    fun keys(): Set<URI> = synchronized(lock) { entries.keys.mapTo(LinkedHashSet()) { it.uri } }

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

    internal fun putForCompile(uri: URI, entry: CachedParse) = synchronized(lock) {
        store(CacheKey(uri), entry)
    }

    /** Keep the newest result available even when open legacy results already fill the cache. */
    private fun store(key: CacheKey, entry: CachedParse) {
        entries[key] = entry
        evictOverflow(keep = key)
    }

    /**
     * Must be called holding the monitor. Evicts closed entries other than [keep], least recently used
     * first. The cap is soft: open documents and [keep] are never evicted, even if they exceed it.
     */
    private fun evictOverflow(keep: CacheKey?) {
        if (entries.size <= maxEntries) return
        val candidates = entries.keys.iterator()
        while (entries.size > maxEntries && candidates.hasNext()) {
            val uri = candidates.next()
            if (uri != keep && (uri.owned || !isOpen(uri.uri))) {
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

    private fun newEntries() = LinkedHashMap<CacheKey, CachedParse>(INITIAL_CAPACITY, LOAD_FACTOR, true)
}
