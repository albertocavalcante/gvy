package com.github.albertocavalcante.groovylsp.cache

import com.github.albertocavalcante.groovycommon.cache.ThreadSafeLRUCache

/**
 * Thread-safe LRU cache implementation with size limits.
 *
 * This class is now a thin wrapper around [ThreadSafeLRUCache] from groovy-common.
 * It maintains backward compatibility with the existing API while delegating
 * all functionality to the shared cache implementation.
 *
 * Features:
 * - Thread-safe operations
 * - Configurable maximum size
 * - LRU eviction policy
 * - O(1) get/put operations
 *
 * @param maxSize Maximum number of entries to keep in cache
 */
class LRUCache<K, V>(maxSize: Int) {
    private val delegate = ThreadSafeLRUCache<K, V>(maxSize)

    /**
     * Get value from cache, updating access order.
     */
    fun get(key: K): V? = delegate.get(key)

    /**
     * Put value in cache, evicting old entries if necessary
     */
    fun put(key: K, value: V): V? {
        delegate.put(key, value)
        return null // Original implementation returned V?, but put didn't use return value
    }

    /**
     * Remove entry from cache
     */
    fun remove(key: K): V? = delegate.remove(key)

    /**
     * Clear all entries
     */
    fun clear() = delegate.clear()

    /**
     * Get current cache size
     */
    fun size(): Int = delegate.size()

    /**
     * Check if cache is empty
     */
    fun isEmpty(): Boolean = delegate.isEmpty()

    /**
     * Get all keys in access order (least to most recently used)
     */
    fun keys(): List<K> = delegate.keys()

    /**
     * Returns an immutable snapshot of the current cache contents.
     */
    fun snapshot(): Map<K, V> = delegate.snapshot()

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats = delegate.getStats().let { stats ->
        CacheStats(
            size = stats.size,
            maxSize = stats.maxSize,
            hitRate = stats.hitRate,
        )
    }

    data class CacheStats(val size: Int, val maxSize: Int, val hitRate: Double)
}
