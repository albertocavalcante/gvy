package com.github.albertocavalcante.groovycommon.cache

import java.util.LinkedHashMap

/**
 * Generic LRU (Least Recently Used) cache implementation.
 *
 * This cache automatically evicts the least recently used entries when the maximum size
 * is exceeded. Access order is maintained, so both `get` and `put` operations update
 * the recency of an entry.
 *
 * **Note**: This implementation is NOT thread-safe. For concurrent access, use
 * [ThreadSafeLRUCache] instead.
 *
 * Features:
 * - Configurable maximum size
 * - LRU eviction policy
 * - O(1) get/put operations
 *
 * @param maxSize Maximum number of entries to keep in cache
 * @param K The type of keys maintained by this cache
 * @param V The type of mapped values
 */
open class LRUCache<K, V>(protected val maxSize: Int) : Cache<K, V> {
    private val cache =
        object : LinkedHashMap<K, V>(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
        }

    override fun get(key: K): V? = cache[key]

    override fun put(key: K, value: V): V? = cache.put(key, value)

    override fun remove(key: K): V? = cache.remove(key)

    override fun clear() = cache.clear()

    override fun size(): Int = cache.size

    override fun contains(key: K): Boolean = cache.containsKey(key)

    /**
     * Check if cache is empty
     */
    open fun isEmpty(): Boolean = cache.isEmpty()

    /**
     * Get all keys in access order (least to most recently used)
     */
    open fun keys(): List<K> = cache.keys.toList()

    /**
     * Returns an immutable snapshot of the current cache contents.
     */
    open fun snapshot(): Map<K, V> = cache.toMap()

    companion object {
        private const val DEFAULT_INITIAL_CAPACITY = 16
        private const val DEFAULT_LOAD_FACTOR = 0.75f
    }
}
