package com.github.albertocavalcante.groovycommon.cache

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Statistics for cache usage and performance.
 *
 * @property size Current number of entries in the cache
 * @property maxSize Maximum number of entries allowed in the cache
 * @property hitRate Cache hit rate (0.0 to 1.0), or 0.0 if not tracked
 */
data class CacheStats(val size: Int, val maxSize: Int, val hitRate: Double)

/**
 * Thread-safe LRU (Least Recently Used) cache implementation.
 *
 * This cache extends [LRUCache] with thread-safety guarantees using read-write locks.
 * Multiple readers can access the cache concurrently, but write operations are exclusive.
 *
 * Features:
 * - Thread-safe operations
 * - Configurable maximum size
 * - LRU eviction policy
 * - O(1) get/put operations
 * - Efficient concurrent reads
 *
 * Performance considerations:
 * - Read operations (get, size, contains) use shared read locks
 * - Write operations (put, remove, clear) use exclusive write locks
 * - The `get` operation requires a write lock due to LRU access order updates
 *
 * @param maxSize Maximum number of entries to keep in cache
 * @param K The type of keys maintained by this cache
 * @param V The type of mapped values
 */
class ThreadSafeLRUCache<K, V>(maxSize: Int) :
    LRUCache<K, V>(maxSize),
    ThreadSafeCache<K, V> {
    private val lock = ReentrantReadWriteLock()

    /**
     * Get value from cache, updating access order.
     *
     * Note: This operation requires a write lock because it updates the access order
     * in the underlying LinkedHashMap.
     */
    override fun get(key: K): V? = lock.write { super.get(key) }

    /**
     * Put value in cache, evicting old entries if necessary
     */
    override fun put(key: K, value: V): V? = lock.write { super.put(key, value) }

    /**
     * Remove entry from cache
     */
    override fun remove(key: K): V? = lock.write { super.remove(key) }

    /**
     * Clear all entries
     */
    override fun clear() = lock.write { super.clear() }

    /**
     * Get current cache size
     */
    override fun size(): Int = lock.read { super.size() }

    /**
     * Check if cache contains a key
     */
    override fun contains(key: K): Boolean = lock.read { super.contains(key) }

    /**
     * Check if cache is empty
     */
    override fun isEmpty(): Boolean = lock.read { super.isEmpty() }

    /**
     * Get all keys in access order (least to most recently used)
     */
    override fun keys(): List<K> = lock.read { super.keys() }

    /**
     * Returns an immutable snapshot of the current cache contents.
     */
    override fun snapshot(): Map<K, V> = lock.read { super.snapshot() }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats = lock.read {
        CacheStats(
            size = super.size(),
            maxSize = maxSize,
            hitRate = 0.0, // Could implement hit rate tracking if needed
        )
    }
}
