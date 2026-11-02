package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bounded-memory behaviour of [CompilationCache]: LRU eviction that never drops open documents. */
class CompilationCacheEvictionTest {

    private fun uri(n: Int) = URI.create("file:///F$n.groovy")
    private val result = mockk<ParseResult>(relaxed = true)

    private fun CompilationCache.putN(n: Int) = put(uri(n), "c$n", result, "fp")

    @Test
    fun `evicts least recently used entries beyond the cap`() {
        val cache = CompilationCache(maxEntries = 3)

        (1..5).forEach { cache.putN(it) }

        assertEquals(setOf(uri(3), uri(4), uri(5)), cache.keys())
        assertNull(cache.get(uri(1)))
        assertNull(cache.get(uri(2)))
    }

    @Test
    fun `a read refreshes recency`() {
        val cache = CompilationCache(maxEntries = 3)
        (1..3).forEach { cache.putN(it) }

        assertNotNull(cache.get(uri(1), "c1", "fp"))
        cache.putN(4)

        assertNotNull(cache.get(uri(1)), "recently read entry must survive")
        assertNull(cache.get(uri(2)), "least recently used entry is evicted")
    }

    @Test
    fun `open documents are never evicted`() {
        val open = setOf(uri(1), uri(2))
        val cache = CompilationCache(maxEntries = 3, isOpen = { it in open })

        (1..10).forEach { cache.putN(it) }

        assertNotNull(cache.get(uri(1)))
        assertNotNull(cache.get(uri(2)))
        assertEquals(3, cache.keys().size)
        assertTrue(uri(10) in cache.keys())
    }

    @Test
    fun `a closed document becomes evictable`() {
        val open = mutableSetOf(uri(1))
        val cache = CompilationCache(maxEntries = 2, isOpen = { it in open })
        (1..3).forEach { cache.putN(it) }
        assertNotNull(cache.get(uri(1)))

        open.clear()
        (4..6).forEach { cache.putN(it) }

        assertNull(cache.get(uri(1)))
        assertEquals(2, cache.keys().size)
    }

    @Test
    fun `fingerprint and content validation are unchanged`() {
        val cache = CompilationCache(maxEntries = 2)
        cache.put(uri(1), "a", result, "fp1")

        assertNotNull(cache.get(uri(1), "a", "fp1"))
        assertNull(cache.get(uri(1), "a", "other"))
        assertNull(cache.get(uri(1), "b", "fp1"))
        assertEquals("a", cache.getWithContent(uri(1))?.first)
    }

    @Test
    fun `concurrent puts and gets stay bounded and consistent`() {
        val cache = CompilationCache(maxEntries = 50)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.execute {
                runCatching {
                    start.await()
                    repeat(2_000) { i ->
                        val n = (t * 1_000 + i) % 400
                        cache.putN(n)
                        cache.get(uri((n * 7) % 400))
                        cache.getWithContent(uri(n))
                        cache.keys()
                    }
                }.onFailure { failures += it }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), "unexpected failures: $failures")
        assertTrue(cache.keys().size <= 50, "cache exceeded its cap: ${cache.keys().size}")
    }
}
