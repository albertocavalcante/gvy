package com.github.groovylsp.bsp.resolver

import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(CachedResolver::class.java)
    private val lock = ReentrantReadWriteLock()

    private var cachedClasspath: Set<ClassPathEntry>? = null
    private var cachedBuildScriptClasspath: Set<Path>? = null
    private var cachedVersion: Long = -1L

    override val classpath: Set<ClassPathEntry>
        get() = lock.read {
            if (shouldInvalidateCache()) {
                refreshCache()
            }
            cachedClasspath!!
        }

    override val buildScriptClasspath: Set<Path>
        get() = lock.read {
            if (shouldInvalidateCache()) {
                refreshCache()
            }
            cachedBuildScriptClasspath!!
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
     * This method upgrades from a read lock to a write lock, checks again
     * if invalidation is needed (double-checked locking pattern), and
     * updates the cached values.
     *
     * NOTE: Must be called within a read lock, which will be temporarily
     * released to acquire a write lock.
     */
    private fun refreshCache() {
        lock.write {
            // Double-check after acquiring write lock (another thread may have refreshed)
            if (shouldInvalidateCache()) {
                val delegateVersion = delegate.currentBuildFileVersion
                logger.debug(
                    "Invalidating cache: version changed from {} to {}",
                    cachedVersion,
                    delegateVersion,
                )

                cachedClasspath = delegate.classpath
                cachedBuildScriptClasspath = delegate.buildScriptClasspath
                cachedVersion = delegateVersion

                logger.debug(
                    "Cache refreshed: {} classpath entries, {} build script entries",
                    cachedClasspath!!.size,
                    cachedBuildScriptClasspath!!.size,
                )
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
            logger.debug("Manually clearing cache")
            cachedClasspath = null
            cachedBuildScriptClasspath = null
            cachedVersion = -1L
        }
    }
}
