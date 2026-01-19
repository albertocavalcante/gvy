package com.github.albertocavalcante.gvy.common.cache

/**
 * Generic cache interface for key-value storage.
 *
 * Provides basic operations for caching data with type-safe keys and values.
 * Implementations may vary in thread-safety, eviction policies, and persistence.
 *
 * @param K The type of keys maintained by this cache
 * @param V The type of mapped values
 */
interface Cache<K, V> {
    /**
     * Returns the value associated with the given key, or null if the key is not present.
     *
     * @param key The key whose associated value is to be returned
     * @return The value to which the specified key is mapped, or null if this cache contains no mapping for the key
     */
    fun get(key: K): V?

    /**
     * Associates the specified value with the specified key in this cache.
     *
     * If the cache previously contained a mapping for the key, the old value is replaced.
     *
     * @param key The key with which the specified value is to be associated
     * @param value The value to be associated with the specified key
     * @return The previous value associated with the key, or null if there was no mapping for the key
     */
    fun put(key: K, value: V): V?

    /**
     * Removes the mapping for a key from this cache if it is present.
     *
     * @param key The key whose mapping is to be removed from the cache
     * @return The previous value associated with the key, or null if there was no mapping for the key
     */
    fun remove(key: K): V?

    /**
     * Removes all mappings from this cache.
     */
    fun clear()

    /**
     * Returns the number of key-value mappings in this cache.
     *
     * @return The number of key-value mappings in this cache
     */
    fun size(): Int

    /**
     * Returns true if this cache contains a mapping for the specified key.
     *
     * @param key The key whose presence in this cache is to be tested
     * @return true if this cache contains a mapping for the specified key
     */
    fun contains(key: K): Boolean
}
