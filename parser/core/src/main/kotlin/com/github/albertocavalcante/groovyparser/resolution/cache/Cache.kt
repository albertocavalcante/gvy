package com.github.albertocavalcante.groovyparser.resolution.cache

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache interface for storing resolved types.
 *
 * Modeled after JavaParser's Cache interface, providing a simple key-value store
 * with optional statistics tracking.
 *
 * @param K The key type
 * @param V The value type
 */
interface Cache<K, V> {
    /** Stores a value in the cache. */
    fun put(key: K, value: V)

    /** Retrieves a value from the cache, or null if not present. */
    fun get(key: K): V?

    /** Retrieves a value, computing and storing it if not present. */
    fun getOrPut(key: K, compute: () -> V): V

    /** Removes a value from the cache. */
    fun remove(key: K)

    /** Clears all entries from the cache. */
    fun clear()

    /** Returns the number of entries in the cache. */
    fun size(): Long

    /** Returns true if the cache is empty. */
    fun isEmpty(): Boolean

    /** Returns true if the cache contains the given key. */
    fun contains(key: K): Boolean

    /** Returns cache statistics. */
    fun stats(): CacheStats
}

/**
 * Statistics about cache performance.
 */
data class CacheStats(val hitCount: Long = 0, val missCount: Long = 0, val putCount: Long = 0) {
    val requestCount: Long get() = hitCount + missCount
    val hitRate: Double get() = if (requestCount == 0L) 0.0 else hitCount.toDouble() / requestCount
    val missRate: Double get() = if (requestCount == 0L) 0.0 else missCount.toDouble() / requestCount
}

/**
 * In-memory cache implementation using WeakHashMap for automatic cleanup.
 *
 * This cache automatically removes entries when the key is no longer strongly referenced,
 * making it suitable for caching AST node types where nodes may be garbage collected.
 */
class InMemoryCache<K : Any, V : Any> private constructor() : Cache<K, V> {

    private val cache = Collections.synchronizedMap(WeakHashMap<K, V>())
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val puts = AtomicLong(0)

    override fun put(key: K, value: V) {
        cache[key] = value
        puts.incrementAndGet()
    }

    override fun get(key: K): V? {
        val value = cache[key]
        if (value != null) {
            hits.incrementAndGet()
        } else {
            misses.incrementAndGet()
        }
        return value
    }

    override fun getOrPut(key: K, compute: () -> V): V {
        cache[key]?.let {
            hits.incrementAndGet()
            return it
        }

        misses.incrementAndGet()
        val computed = compute()
        put(key, computed)
        return computed
    }

    override fun remove(key: K) {
        cache.remove(key)
    }

    override fun clear() {
        cache.clear()
    }

    override fun size(): Long = cache.size.toLong()

    override fun isEmpty(): Boolean = cache.isEmpty()

    override fun contains(key: K): Boolean = cache.containsKey(key)

    override fun stats(): CacheStats = CacheStats(
        hitCount = hits.get(),
        missCount = misses.get(),
        putCount = puts.get(),
    )

    companion object {
        /** Creates a new in-memory cache with automatic weak reference cleanup. */
        fun <K : Any, V : Any> create(): InMemoryCache<K, V> = InMemoryCache()
    }
}

/**
 * A no-op cache that doesn't actually cache anything.
 * Useful for testing or when caching is not desired.
 */
class NoCache<K : Any, V : Any> : Cache<K, V> {
    override fun put(key: K, value: V) {
        /* no-op */
    }

    override fun get(key: K): V? = null
    override fun getOrPut(key: K, compute: () -> V): V = compute()
    override fun remove(key: K) {
        /* no-op */
    }

    override fun clear() {
        /* no-op */
    }

    override fun size(): Long = 0
    override fun isEmpty(): Boolean = true
    override fun contains(key: K): Boolean = false
    override fun stats(): CacheStats = CacheStats()

    companion object {
        private val INSTANCE = NoCache<Any, Any>()

        @Suppress("UNCHECKED_CAST")
        fun <K : Any, V : Any> instance(): NoCache<K, V> = INSTANCE as NoCache<K, V>
    }
}

/**
 * Type-specific cache for storing resolved types keyed by AST nodes.
 *
 * This is the main cache used by the type inference system to avoid
 * recomputing types for the same expression multiple times.
 */
class TypeCache private constructor(private val delegate: Cache<Any, ResolvedType>) :
    Cache<Any, ResolvedType> by delegate {

    companion object {
        /** Creates a type cache with in-memory storage. */
        fun create(): TypeCache = TypeCache(InMemoryCache.create())

        /** Creates a no-op type cache that doesn't store anything. */
        fun noCache(): TypeCache = TypeCache(NoCache.instance())
    }
}
