package com.github.albertocavalcante.groovylsp.cache

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LRUCacheTest {

    @Test
    fun `evicts least recently used on put`() {
        val cache = LRUCache<String, Int>(2)

        cache.put("A", 1)
        cache.put("B", 2)
        cache.get("A")
        cache.put("C", 3)

        assertEquals(listOf("A", "C"), cache.keys())
    }

    @Test
    fun `updates access order on get`() {
        val cache = LRUCache<String, Int>(3)

        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)

        assertEquals(listOf("A", "B", "C"), cache.keys())

        cache.get("A")
        assertEquals(listOf("B", "C", "A"), cache.keys())

        cache.get("B")
        assertEquals(listOf("C", "A", "B"), cache.keys())
    }

    @Test
    fun `snapshot preserves access order`() {
        val cache = LRUCache<String, Int>(3)

        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        cache.get("A")

        val snapshot = cache.snapshot()

        assertEquals(listOf("B", "C", "A"), snapshot.keys.toList())
    }
}
