package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseResult
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache entry storing content, parse result, and configuration fingerprint.
 * The fingerprint ensures cache coherency when workspace configuration changes (Issue #743).
 */
private data class CacheEntry(val content: String, val parseResult: ParseResult, val configFingerprint: String)

class CompilationCache {
    private val cache = ConcurrentHashMap<URI, CacheEntry>()

    /**
     * Gets cached result if content AND configuration fingerprint match.
     * Returns null if not cached or if either content or fingerprint has changed.
     */
    fun get(uri: URI, content: String, configFingerprint: String): ParseResult? {
        val entry = cache[uri] ?: return null
        return if (entry.content == content && entry.configFingerprint == configFingerprint) {
            entry.parseResult
        } else {
            null
        }
    }

    /**
     * Gets cached result if content matches (ignores fingerprint).
     * For backward compatibility with callers that don't have fingerprint context.
     */
    fun get(uri: URI, content: String): ParseResult? {
        val entry = cache[uri] ?: return null
        return if (entry.content == content) entry.parseResult else null
    }

    fun get(uri: URI): ParseResult? = cache[uri]?.parseResult

    fun getWithContent(uri: URI): Pair<String, ParseResult>? = cache[uri]?.let { it.content to it.parseResult }

    /**
     * Stores a parse result with its content and configuration fingerprint.
     */
    fun put(uri: URI, content: String, parseResult: ParseResult, configFingerprint: String) {
        cache[uri] = CacheEntry(content, parseResult, configFingerprint)
    }

    /**
     * Stores a parse result without fingerprint (backward compatibility).
     * Uses empty fingerprint. Entries stored this way can be retrieved via get(uri, content)
     * which ignores fingerprint, but will NOT match get(uri, content, fingerprint) unless
     * the fingerprint parameter is also empty.
     */
    fun put(uri: URI, content: String, parseResult: ParseResult) {
        cache[uri] = CacheEntry(content, parseResult, "")
    }

    fun invalidate(uri: URI) {
        cache.remove(uri)
    }

    fun clear() {
        cache.clear()
    }

    fun keys(): Set<URI> = cache.keys

    fun getStatistics() = mapOf(
        "cachedResults" to cache.size,
    )
}
