package com.github.albertocavalcante.groovylsp.cache

import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe LRU cache implementation with size limits.
 *
 * Features:
 * - Thread-safe operations
 * - Configurable maximum size
 * - LRU eviction policy
 * - O(1) get/put operations
 *
 * @param maxSize Maximum number of entries to keep in cache
 */
class LRUCache<K, V>(private val maxSize: Int) {
    private val lock = ReentrantReadWriteLock()
    private val cache =
        object : LinkedHashMap<K, V>(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
        }

    /**
     * Get value from cache, updating access order.
     */
    fun get(key: K): V? = lock.write { cache[key] }

    /**
     * Put value in cache, evicting old entries if necessary
     */
    fun put(key: K, value: V): V? = lock.write { cache.put(key, value) }

    /**
     * Remove entry from cache
     */
    fun remove(key: K): V? = lock.write { cache.remove(key) }

    /**
     * Clear all entries
     */
    fun clear() = lock.write { cache.clear() }

    /**
     * Get current cache size
     */
    fun size(): Int = lock.read { cache.size }

    /**
     * Check if cache is empty
     */
    fun isEmpty(): Boolean = lock.read { cache.isEmpty() }

    /**
     * Get all keys in access order (least to most recently used)
     */
    fun keys(): List<K> = lock.read { cache.keys.toList() }

    /**
     * Returns an immutable snapshot of the current cache contents.
     */
    fun snapshot(): Map<K, V> = lock.read {
        LinkedHashMap<K, V>(cache.size).apply { putAll(cache) }
    }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats = lock.read {
        CacheStats(
            size = cache.size,
            maxSize = maxSize,
            hitRate = 0.0, // Could implement hit rate tracking if needed
        )
    }

    data class CacheStats(val size: Int, val maxSize: Int, val hitRate: Double)
}

private const val DEFAULT_INITIAL_CAPACITY = 16
private const val DEFAULT_LOAD_FACTOR = 0.75f
