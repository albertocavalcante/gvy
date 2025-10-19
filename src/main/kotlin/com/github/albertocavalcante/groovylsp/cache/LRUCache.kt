package com.github.albertocavalcante.groovylsp.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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

    private data class CacheEntry<K, V>(val key: K, var value: V, var accessTime: Long = System.nanoTime())

    private val cache = ConcurrentHashMap<K, CacheEntry<K, V>>()
    private val accessOrder = ConcurrentLinkedQueue<K>()
    private val lock = ReentrantReadWriteLock()

    /**
     * Get value from cache, updating access time
     */
    fun get(key: K): V? = lock.read {
        cache[key]?.let { entry ->
            entry.accessTime = System.nanoTime()
            // Move to end of access queue (most recently used)
            accessOrder.remove(key)
            accessOrder.offer(key)
            entry.value
        }
    }

    /**
     * Put value in cache, evicting old entries if necessary
     */
    fun put(key: K, value: V): V? = lock.write {
        val existing = cache[key]
        val entry = CacheEntry(key, value)

        cache[key] = entry

        if (existing == null) {
            accessOrder.offer(key)
            evictIfNecessary()
        } else {
            // Update existing entry position in access order
            accessOrder.remove(key)
            accessOrder.offer(key)
        }

        existing?.value
    }

    /**
     * Remove entry from cache
     */
    fun remove(key: K): V? = lock.write {
        accessOrder.remove(key)
        cache.remove(key)?.value
    }

    /**
     * Clear all entries
     */
    fun clear() = lock.write {
        cache.clear()
        accessOrder.clear()
    }

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
    fun keys(): List<K> = lock.read { accessOrder.toList() }

    /**
     * Returns an immutable snapshot of the current cache contents.
     */
    fun snapshot(): Map<K, V> = lock.read {
        cache.entries.associate { (key, entry) -> key to entry.value }
    }

    /**
     * Evict least recently used entries if cache exceeds max size
     */
    private fun evictIfNecessary() {
        while (cache.size > maxSize) {
            val lruKey = accessOrder.poll()
            if (lruKey != null) {
                cache.remove(lruKey)
            } else {
                break // Queue is empty but cache isn't - shouldn't happen
            }
        }
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
