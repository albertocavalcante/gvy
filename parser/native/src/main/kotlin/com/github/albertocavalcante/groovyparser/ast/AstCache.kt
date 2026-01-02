package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for storing parsed ASTs and their associated metadata.
 * Thread-safe implementation using ConcurrentHashMap with LRU eviction.
 */
class AstCache {
    companion object {
        private const val MAX_CACHE_SIZE = 100
    }

    private data class CacheEntry(
        val ast: ASTNode,
        val contentHash: Int,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val cache = ConcurrentHashMap<URI, CacheEntry>()
    private val accessOrder = ConcurrentHashMap<URI, Long>()

    /**
     * Stores an AST in the cache for the given URI and content.
     */
    fun put(uri: URI, content: String, ast: ASTNode) {
        val contentHash = content.hashCode()
        val timestamp = System.currentTimeMillis()

        cache[uri] = CacheEntry(ast, contentHash, timestamp)
        accessOrder[uri] = timestamp

        // Evict oldest entries if cache exceeds max size
        if (cache.size > MAX_CACHE_SIZE) {
            evictOldestEntries()
        }
    }

    /**
     * Retrieves an AST from the cache if it exists and the content hasn't changed.
     * Returns null if not cached or if the content has changed.
     */
    fun get(uri: URI, content: String): ASTNode? {
        val entry = cache[uri] ?: return null
        val contentHash = content.hashCode()

        return if (entry.contentHash == contentHash) {
            // Update access time for LRU
            accessOrder[uri] = System.currentTimeMillis()
            entry.ast
        } else {
            // Content has changed, remove stale entry
            cache.remove(uri)
            accessOrder.remove(uri)
            null
        }
    }

    /**
     * Removes an entry from the cache.
     */
    fun remove(uri: URI) {
        cache.remove(uri)
        accessOrder.remove(uri)
    }

    /**
     * Clears all cached entries.
     */
    fun clear() {
        cache.clear()
        accessOrder.clear()
    }

    /**
     * Returns whether an AST is cached for the given URI with the given content.
     */
    fun contains(uri: URI, content: String): Boolean {
        val entry = cache[uri] ?: return false
        return entry.contentHash == content.hashCode()
    }

    /**
     * Returns the number of cached entries.
     */
    fun size(): Int = cache.size

    /**
     * Returns all cached URIs.
     */
    fun getCachedUris(): Set<URI> = cache.keys.toSet()

    /**
     * Gets the AST for a URI without content validation.
     * Use this only when you're sure the cache is still valid.
     */
    fun getUnchecked(uri: URI): ASTNode? {
        val entry = cache[uri]
        if (entry != null) {
            // Update access time for LRU even for unchecked access
            accessOrder[uri] = System.currentTimeMillis()
        }
        return entry?.ast
    }

    /**
     * Evicts the oldest entries when cache exceeds maximum size.
     */
    private fun evictOldestEntries() {
        val entriesToRemove = cache.size - MAX_CACHE_SIZE + 1
        if (entriesToRemove <= 0) return

        val sortedByAccess = accessOrder.toList().sortedBy { it.second }
        repeat(entriesToRemove) { index ->
            if (index < sortedByAccess.size) {
                val uriToRemove = sortedByAccess[index].first
                cache.remove(uriToRemove)
                accessOrder.remove(uriToRemove)
            }
        }
    }
}
