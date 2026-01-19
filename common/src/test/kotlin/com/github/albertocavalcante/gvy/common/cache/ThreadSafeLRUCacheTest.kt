package com.github.albertocavalcante.gvy.common.cache

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("ThreadSafeLRUCache")
class ThreadSafeLRUCacheTest {

    @Test
    fun `put and get basic operation`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        cache.put("A", 1)
        assertEquals(1, cache.get("A"))
    }

    @Test
    fun `get returns null for non-existent key`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `put replaces existing value`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        cache.put("A", 1)
        val oldValue = cache.put("A", 10)
        assertEquals(1, oldValue)
        assertEquals(10, cache.get("A"))
    }

    @Test
    fun `remove deletes entry and returns old value`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        cache.put("A", 1)
        val removed = cache.remove("A")
        assertEquals(1, removed)
        assertNull(cache.get("A"))
    }

    @Test
    fun `clear removes all entries`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.clear()
        assertEquals(0, cache.size())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `size returns correct count`() {
        val cache = ThreadSafeLRUCache<String, Int>(5)
        assertEquals(0, cache.size())
        cache.put("A", 1)
        assertEquals(1, cache.size())
        cache.put("B", 2)
        assertEquals(2, cache.size())
    }

    @Test
    fun `contains checks key presence`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        assertFalse(cache.contains("A"))
        cache.put("A", 1)
        assertTrue(cache.contains("A"))
    }

    @Test
    fun `isEmpty works correctly`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        assertTrue(cache.isEmpty())
        cache.put("A", 1)
        assertFalse(cache.isEmpty())
    }

    @Test
    fun `evicts least recently used when exceeding maxSize`() {
        val cache = ThreadSafeLRUCache<String, Int>(2)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        assertNull(cache.get("A"))
        assertEquals(2, cache.get("B"))
        assertEquals(3, cache.get("C"))
    }

    @Test
    fun `keys returns all keys in access order`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        assertEquals(listOf("A", "B", "C"), cache.keys())
    }

    @Test
    fun `snapshot returns immutable copy`() {
        val cache = ThreadSafeLRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        val snapshot = cache.snapshot()
        assertEquals(mapOf("A" to 1, "B" to 2), snapshot)
        cache.put("C", 3)
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `getStats returns correct statistics`() {
        val cache = ThreadSafeLRUCache<String, Int>(5)
        cache.put("A", 1)
        cache.put("B", 2)
        val stats = cache.getStats()
        assertEquals(2, stats.size)
        assertEquals(5, stats.maxSize)
        assertEquals(0.0, stats.hitRate)
    }

    @Test
    fun `implements ThreadSafeCache interface`() {
        val cache: ThreadSafeCache<String, Int> = ThreadSafeLRUCache(3)
        cache.put("A", 1)
        assertEquals(1, cache.get("A"))
    }

    @Test
    fun `concurrent puts from multiple threads`() {
        val cache = ThreadSafeLRUCache<Int, String>(100)
        val threadCount = 10
        val operationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { threadId ->
            executor.submit {
                repeat(operationsPerThread) { i ->
                    val key = threadId * operationsPerThread + i
                    cache.put(key, "value-$key")
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Cache should have exactly 100 entries (maxSize)
        assertEquals(100, cache.size())
    }

    @Test
    fun `concurrent gets and puts maintain consistency`() {
        val cache = ThreadSafeLRUCache<String, AtomicInteger>(10)
        val key = "counter"
        cache.put(key, AtomicInteger(0))

        val threadCount = 20
        val operationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                repeat(operationsPerThread) {
                    cache.get(key)?.incrementAndGet()
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val finalValue = cache.get(key)?.get()
        assertEquals(threadCount * operationsPerThread, finalValue)
    }

    @Test
    fun `concurrent operations with barrier synchronization`() {
        val cache = ThreadSafeLRUCache<Int, String>(50)
        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { threadId ->
            executor.submit {
                try {
                    // Synchronize thread start
                    barrier.await(5, TimeUnit.SECONDS)

                    repeat(100) { i ->
                        val key = threadId * 100 + i
                        cache.put(key, "value-$key")
                        cache.get(key)
                        if (i % 10 == 0) {
                            cache.size()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Cache should respect maxSize
        assertEquals(50, cache.size())
    }

    @Test
    fun `concurrent remove operations`() {
        val cache = ThreadSafeLRUCache<Int, String>(100)

        // Pre-populate cache
        repeat(100) { i ->
            cache.put(i, "value-$i")
        }

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { threadId ->
            executor.submit {
                repeat(10) { i ->
                    cache.remove(threadId * 10 + i)
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(0, cache.size())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `concurrent clear and put operations`() {
        val cache = ThreadSafeLRUCache<Int, String>(100)
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)

        // Thread 1 & 2: continuous puts
        repeat(2) { threadId ->
            executor.submit {
                repeat(50) { i ->
                    cache.put(threadId * 50 + i, "value-$i")
                    Thread.sleep(1) // Small delay
                }
                latch.countDown()
            }
        }

        // Thread 3 & 4: periodic clears
        repeat(2) {
            executor.submit {
                repeat(5) {
                    Thread.sleep(10)
                    cache.clear()
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Cache should be in valid state (size <= maxSize)
        assertTrue(cache.size() <= 100)
    }

    @Test
    fun `snapshot is thread-safe and consistent`() {
        val cache = ThreadSafeLRUCache<Int, String>(50)
        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val snapshots = mutableListOf<Map<Int, String>>()

        repeat(threadCount) { threadId ->
            executor.submit {
                repeat(20) { i ->
                    cache.put(threadId * 20 + i, "value-$i")
                }
                synchronized(snapshots) {
                    snapshots.add(cache.snapshot())
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // All snapshots should be valid maps
        snapshots.forEach { snapshot ->
            assertTrue(snapshot.size <= 50)
        }
    }

    @Test
    fun `keys method is thread-safe`() {
        val cache = ThreadSafeLRUCache<Int, String>(50)
        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { threadId ->
            executor.submit {
                repeat(20) { i ->
                    cache.put(threadId * 20 + i, "value-$i")
                    cache.keys() // Should not throw
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val keys = cache.keys()
        assertEquals(cache.size(), keys.size)
    }
}
