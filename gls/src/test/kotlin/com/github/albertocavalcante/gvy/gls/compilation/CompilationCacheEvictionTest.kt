// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
        assertNull(cache.getWithContent(uri(1), "fp"))
        assertNull(cache.getWithContent(uri(2), "fp"))
    }

    @Test
    fun `a read refreshes recency`() {
        val cache = CompilationCache(maxEntries = 3)
        (1..3).forEach { cache.putN(it) }

        assertNotNull(cache.get(uri(1), "c1", "fp"))
        cache.putN(4)

        assertNotNull(cache.getWithContent(uri(1), "fp"), "recently read entry must survive")
        assertNull(cache.getWithContent(uri(2), "fp"), "least recently used entry is evicted")
    }

    @Test
    fun `open documents are never evicted`() {
        val open = setOf(uri(1), uri(2))
        val cache = CompilationCache(maxEntries = 3, isOpen = { it in open })

        (1..10).forEach { cache.putN(it) }

        assertNotNull(cache.getWithContent(uri(1), "fp"))
        assertNotNull(cache.getWithContent(uri(2), "fp"))
        assertEquals(3, cache.keys().size)
        assertTrue(uri(10) in cache.keys())
    }

    @Test
    fun `a closed document becomes evictable`() {
        val open = mutableSetOf(uri(1))
        val cache = CompilationCache(maxEntries = 2, isOpen = { it in open })
        (1..3).forEach { cache.putN(it) }
        assertNotNull(cache.getWithContent(uri(1), "fp"))

        open.clear()
        (4..6).forEach { cache.putN(it) }

        assertNull(cache.getWithContent(uri(1), "fp"))
        assertEquals(2, cache.keys().size)
    }

    @Test
    fun `fingerprint and content validation are unchanged`() {
        val cache = CompilationCache(maxEntries = 2)
        cache.put(uri(1), "a", result, "fp1")

        assertNotNull(cache.get(uri(1), "a", "fp1"))
        assertNull(cache.get(uri(1), "a", "other"))
        assertNull(cache.get(uri(1), "b", "fp1"))
        assertEquals("a", cache.getWithContent(uri(1), "fp1")?.first)
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
                        cache.getWithContent(uri((n * 7) % 400), "fp")
                        cache.getWithContent(uri(n), "fp")
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

    @Test
    fun `an entry just stored survives even when open documents fill the cap`() {
        // Go-to-definition compiles a closed file and reads its AST back at once; with open documents
        // at the cap, the new entry was the only eviction candidate and vanished on insert.
        val open = setOf(uri(1), uri(2))
        val cache = CompilationCache(maxEntries = 2, isOpen = { it in open })
        cache.putN(1)
        cache.putN(2)

        cache.putN(3)

        assertNotNull(cache.get(uri(3), "c3", "fp"), "the closed file just compiled must be readable")
        assertNotNull(cache.getWithContent(uri(1), "fp"))
        assertNotNull(cache.getWithContent(uri(2), "fp"))
    }

    @Test
    fun `shrinking the cap evicts least recently used closed entries at once`() {
        val cache = CompilationCache(maxEntries = 5)
        (1..5).forEach { cache.putN(it) }

        cache.resize(2)

        assertEquals(setOf(uri(4), uri(5)), cache.keys())
    }
}
