package com.github.albertocavalcante.groovycommon.cache

/**
 * Marker interface for thread-safe cache implementations.
 *
 * Implementations of this interface guarantee that all cache operations are safe for
 * concurrent access from multiple threads without external synchronization.
 *
 * Thread-safety typically comes with a performance cost compared to non-thread-safe
 * implementations. Use this interface when the cache will be accessed from multiple threads.
 *
 * @param K The type of keys maintained by this cache
 * @param V The type of mapped values
 */
interface ThreadSafeCache<K, V> : Cache<K, V>
