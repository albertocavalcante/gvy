package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovycommon.cache.ThreadSafeLRUCache
import org.codehaus.groovy.ast.ASTNode
import java.net.URI

/**
 * Cache for storing parsed ASTs and their associated metadata.
 * Thread-safe implementation using ThreadSafeLRUCache with LRU eviction.
 */
class AstCache {
    companion object {
        private const val MAX_CACHE_SIZE = 100
    }

    private data class CacheEntry(val ast: ASTNode, val contentHash: Int)

    private val cache = ThreadSafeLRUCache<URI, CacheEntry>(MAX_CACHE_SIZE)

    /**
     * Stores an AST in the cache for the given URI and content.
     */
    fun put(uri: URI, content: String, ast: ASTNode) {
        val contentHash = content.hashCode()
        cache.put(uri, CacheEntry(ast, contentHash))
    }

    /**
     * Retrieves an AST from the cache if it exists and the content hasn't changed.
     * Returns null if not cached or if the content has changed.
     */
    fun get(uri: URI, content: String): ASTNode? {
        val entry = cache.get(uri) ?: return null
        val contentHash = content.hashCode()

        return if (entry.contentHash == contentHash) {
            entry.ast
        } else {
            // Content has changed, remove stale entry
            cache.remove(uri)
            null
        }
    }

    /**
     * Removes an entry from the cache.
     */
    fun remove(uri: URI) {
        cache.remove(uri)
    }

    /**
     * Clears all cached entries.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Returns whether an AST is cached for the given URI with the given content.
     */
    fun contains(uri: URI, content: String): Boolean {
        val entry = cache.get(uri) ?: return false
        return entry.contentHash == content.hashCode()
    }

    /**
     * Returns the number of cached entries.
     */
    fun size(): Int = cache.size()

    /**
     * Returns all cached URIs.
     */
    fun getCachedUris(): Set<URI> = cache.keys().toSet()

    /**
     * Gets the AST for a URI without content validation.
     * Use this only when you're sure the cache is still valid.
     */
    fun getUnchecked(uri: URI): ASTNode? {
        val entry = cache.get(uri)
        return entry?.ast
    }
}
