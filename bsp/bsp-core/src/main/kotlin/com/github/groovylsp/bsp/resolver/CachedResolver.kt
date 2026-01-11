package com.github.groovylsp.bsp.resolver

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A thread-safe caching wrapper for [ClassPathResolver] that invalidates the cache
 * when the build file version changes.
 *
 * This resolver wraps another resolver and caches its results until the
 * [currentBuildFileVersion] changes, at which point the cache is invalidated
 * and the underlying resolver is queried again.
 *
 * Thread safety is guaranteed through read-write locks:
 * - Multiple threads can read cached values concurrently
 * - Cache updates are synchronized with exclusive write locks
 *
 * Example usage:
 * ```kotlin
 * val expensiveResolver = BspResolver(workspace)
 * val cachedResolver = CachedResolver(expensiveResolver)
 *
 * // First call: queries underlying resolver
 * val classpath1 = cachedResolver.classpath
 *
 * // Second call: returns cached result (if version unchanged)
 * val classpath2 = cachedResolver.classpath
 * ```
 *
 * @property delegate The underlying resolver to cache
 */
class CachedResolver(private val delegate: ClassPathResolver) : ClassPathResolver {

    private val logger = KotlinLogging.logger {}
    private val lock = ReentrantReadWriteLock()

    private var cachedClasspath: Set<ClassPathEntry>? = null
    private var cachedBuildScriptClasspath: Set<Path>? = null
    private var cachedVersion: Long = -1L

    override val classpath: Set<ClassPathEntry>
        get() {
            // Fast path: check with read lock
            lock.read {
                if (!shouldInvalidateCache()) {
                    return cachedClasspath!!
                }
            }
            // Slow path: refresh with write lock
            refreshCache()
            return lock.read { cachedClasspath!! }
        }

    override val buildScriptClasspath: Set<Path>
        get() {
            // Fast path: check with read lock
            lock.read {
                if (!shouldInvalidateCache()) {
                    return cachedBuildScriptClasspath!!
                }
            }
            // Slow path: refresh with write lock
            refreshCache()
            return lock.read { cachedBuildScriptClasspath!! }
        }

    override val currentBuildFileVersion: Long
        get() = delegate.currentBuildFileVersion

    /**
     * Checks if the cache should be invalidated.
     *
     * The cache is invalidated when:
     * - No cache exists yet (cachedVersion == -1)
     * - The delegate's build file version differs from the cached version
     *
     * NOTE: This method must be called within a lock (read or write).
     */
    private fun shouldInvalidateCache(): Boolean {
        val delegateVersion = delegate.currentBuildFileVersion
        return cachedVersion == -1L || cachedVersion != delegateVersion
    }

    /**
     * Refreshes the cache by querying the delegate resolver.
     *
     * Uses double-checked locking pattern: checks if invalidation is needed
     * after acquiring write lock (another thread may have refreshed between
     * the read lock check and acquiring the write lock).
     *
     * NOTE: This method acquires a write lock and should not be called
     * from within any other lock.
     */
    private fun refreshCache() {
        lock.write {
            // Double-check after acquiring write lock (another thread may have refreshed)
            if (shouldInvalidateCache()) {
                val delegateVersion = delegate.currentBuildFileVersion
                logger.debug { "Invalidating cache: version changed from $cachedVersion to $delegateVersion" }

                cachedClasspath = delegate.classpath
                cachedBuildScriptClasspath = delegate.buildScriptClasspath
                cachedVersion = delegateVersion

                logger.debug {
                    "Cache refreshed: ${cachedClasspath!!.size} classpath entries, ${cachedBuildScriptClasspath!!.size} build script entries"
                }
            }
        }
    }

    /**
     * Manually clears the cache, forcing the next access to re-query the delegate.
     *
     * This is useful for testing or forcing a refresh even when the version
     * hasn't changed.
     */
    fun clearCache() {
        lock.write {
            logger.debug { "Manually clearing cache" }
            cachedClasspath = null
            cachedBuildScriptClasspath = null
            cachedVersion = -1L
        }
    }
}
