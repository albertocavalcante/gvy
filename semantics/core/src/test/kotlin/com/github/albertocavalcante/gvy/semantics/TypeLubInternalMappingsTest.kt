package com.github.albertocavalcante.gvy.semantics

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

class TypeLubInternalMappingsTest {

    @Test
    fun `KNOWN_FOR_RANK includes BigInteger and BigDecimal wrappers`() {
        val typeLubClass = TypeLub::class.java

        val rankBigInteger = readPrivateIntConstant(typeLubClass, "RANK_BIG_INTEGER")
        val rankBigDecimal = readPrivateIntConstant(typeLubClass, "RANK_BIG_DECIMAL")

        val knownForRankField = typeLubClass.getDeclaredField("KNOWN_FOR_RANK").apply { isAccessible = true }
        val receiver = TypeLub

        @Suppress("UNCHECKED_CAST")
        val map = knownForRankField.get(receiver) as Map<Int, SemanticType>

        val bigInteger = map[rankBigInteger] as? SemanticType.Known
        val bigDecimal = map[rankBigDecimal] as? SemanticType.Known

        assertNotNull(bigInteger)
        assertNotNull(bigDecimal)
        assertEquals("java.math.BigInteger", bigInteger!!.fqn)
        assertEquals("java.math.BigDecimal", bigDecimal!!.fqn)
    }

    @AfterEach
    fun clearCache() {
        // Clear the cache after each test to avoid interference
        clearAncestorCache()
    }

    @Test
    fun `ANCESTOR_CACHE evicts oldest entries when exceeding max size`() {
        val maxSize = readPrivateIntConstant(TypeLub::class.java, "MAX_ANCESTOR_CACHE_SIZE")

        // Clear cache first
        clearAncestorCache()

        // Populate cache with synthetic entries beyond the max size
        // We'll create (maxSize + 100) unique FQNs and trigger ancestor lookups
        val syntheticTypes = (1..maxSize + 100).map { index ->
            SemanticType.Known("test.synthetic.Type$index")
        }

        // Trigger ancestor computation for each type by computing LUB with a known type
        // This will populate the cache with entries
        syntheticTypes.forEach { type ->
            TypeLub.lub(type, TypeConstants.STRING)
        }

        // Cache size should not exceed max size
        val finalSize = getAncestorCacheSize()
        assertTrue(
            finalSize <= maxSize,
            "Cache size $finalSize should not exceed MAX_ANCESTOR_CACHE_SIZE $maxSize",
        )
    }

    @Test
    fun `ANCESTOR_CACHE exhibits LRU behavior - recently accessed entries are retained`() {
        val maxSize = readPrivateIntConstant(TypeLub::class.java, "MAX_ANCESTOR_CACHE_SIZE")

        // Clear cache first
        clearAncestorCache()

        // Fill cache almost to capacity with entries that will be evicted
        val fillSize = maxSize - 100 // Leave room for recently accessed entries
        val oldTypes = (1..fillSize).map { index ->
            SemanticType.Known("test.old.Type$index")
        }

        oldTypes.forEach { type ->
            TypeLub.lub(type, TypeConstants.STRING)
        }

        // Now add a few entries that we will frequently access
        val recentlyAccessedTypes = listOf(
            SemanticType.Known("java.util.ArrayList"),
            SemanticType.Known("java.util.LinkedList"),
            SemanticType.Known("java.lang.Integer"),
            SemanticType.Known("java.lang.Long"),
            SemanticType.Known("java.math.BigInteger"),
        )

        recentlyAccessedTypes.forEach { type ->
            TypeLub.lub(type, TypeConstants.STRING)
        }

        // Access these entries multiple times to mark them as "hot" in the LRU cache
        repeat(3) {
            recentlyAccessedTypes.forEach { type ->
                TypeLub.lub(type, TypeConstants.STRING)
            }
        }

        // Now fill cache with 200 more entries, pushing out old entries
        val newTypes = (1..200).map { index ->
            SemanticType.Known("test.new.Type$index")
        }
        newTypes.forEach { type ->
            TypeLub.lub(type, TypeConstants.STRING)
        }

        // Check that recently accessed entries are still in cache
        // They should be retained because they were accessed more recently
        val recentlyAccessedFqns = recentlyAccessedTypes.map { it.fqn }
        val keysInCache = getAncestorCacheKeys()

        val retainedCount = recentlyAccessedFqns.count { fqn -> fqn in keysInCache }

        // At least some of the recently accessed entries should still be in cache
        // With proper LRU behavior, all 5 should be retained since they were accessed
        // most recently and we only added 200 new entries
        assertTrue(
            retainedCount >= 3,
            "Recently accessed entries should be retained in LRU cache, but found $retainedCount out of 5 retained",
        )
    }

    @Test
    fun `ANCESTOR_CACHE handles concurrent access without corruption`() {
        // Clear cache first
        clearAncestorCache()

        val threadCount = 10
        val operationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // Each thread will compute LUBs with different types concurrently
        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    repeat(operationsPerThread) { opIndex ->
                        val type1 = SemanticType.Known("test.concurrent.Thread${threadIndex}Type$opIndex")
                        val type2 = TypeConstants.STRING
                        TypeLub.lub(type1, type2)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all threads to complete
        val completed = latch.await(30, TimeUnit.SECONDS)
        assertTrue(completed, "Concurrent operations should complete within timeout")

        executor.shutdown()
        assertTrue(
            executor.awaitTermination(5, TimeUnit.SECONDS),
            "Executor should terminate cleanly",
        )

        // Verify cache is in a consistent state (no corruption)
        val finalSize = getAncestorCacheSize()
        val maxSize = readPrivateIntConstant(TypeLub::class.java, "MAX_ANCESTOR_CACHE_SIZE")
        assertTrue(
            finalSize <= maxSize,
            "Cache size $finalSize should not exceed max size $maxSize after concurrent access",
        )
    }

    @Test
    fun `ANCESTOR_CACHE size never exceeds MAX_ANCESTOR_CACHE_SIZE`() {
        val maxSize = readPrivateIntConstant(TypeLub::class.java, "MAX_ANCESTOR_CACHE_SIZE")

        // Clear cache first
        clearAncestorCache()

        // Add many more entries than the max size
        val overflowCount = maxSize * 2
        repeat(overflowCount) { index ->
            val type = SemanticType.Known("test.overflow.Type$index")
            TypeLub.lub(type, TypeConstants.STRING)

            // Check that size never exceeds max
            val currentSize = getAncestorCacheSize()
            assertTrue(
                currentSize <= maxSize,
                "Cache size $currentSize exceeded MAX_ANCESTOR_CACHE_SIZE $maxSize at iteration $index",
            )
        }

        // Final verification
        val finalSize = getAncestorCacheSize()
        assertTrue(
            finalSize <= maxSize,
            "Final cache size $finalSize should not exceed MAX_ANCESTOR_CACHE_SIZE $maxSize",
        )
    }

    // --- Helper Methods ---

    private fun readPrivateIntConstant(clazz: Class<*>, name: String): Int {
        val field = clazz.getDeclaredField(name).apply { isAccessible = true }
        val receiver = TypeLub
        return field.get(receiver) as Int
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAncestorCache(): MutableMap<String, Set<String>> {
        val field = TypeLub::class.java.getDeclaredField("ANCESTOR_CACHE").apply {
            isAccessible = true
        }
        return field.get(TypeLub) as MutableMap<String, Set<String>>
    }

    private fun getAncestorCacheLock(): ReentrantReadWriteLock {
        val field = TypeLub::class.java.getDeclaredField("ancestorCacheLock").apply {
            isAccessible = true
        }
        return field.get(TypeLub) as ReentrantReadWriteLock
    }

    /**
     * Thread-safe method to clear the ancestor cache.
     * Acquires write lock before clearing.
     */
    private fun clearAncestorCache() {
        val lock = getAncestorCacheLock()
        val cache = getAncestorCache()
        lock.write {
            cache.clear()
        }
    }

    /**
     * Thread-safe method to get the size of the ancestor cache.
     * Acquires write lock before reading size (because accessOrder=true makes reads modify the map).
     */
    private fun getAncestorCacheSize(): Int {
        val lock = getAncestorCacheLock()
        val cache = getAncestorCache()
        return lock.write {
            cache.size
        }
    }

    /**
     * Thread-safe method to get the keys of the ancestor cache.
     * Acquires write lock before reading keys (because accessOrder=true makes reads modify the map).
     */
    private fun getAncestorCacheKeys(): Set<String> {
        val lock = getAncestorCacheLock()
        val cache = getAncestorCache()
        return lock.write {
            cache.keys.toSet()
        }
    }
}
