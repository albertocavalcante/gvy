package com.github.albertocavalcante.gvy.common.cache

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("LRUCache")
class LRUCacheTest {

    @Test
    fun `put and get basic operation`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        assertEquals(1, cache.get("A"))
    }

    @Test
    fun `get returns null for non-existent key`() {
        val cache = LRUCache<String, Int>(3)
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `put replaces existing value`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        val oldValue = cache.put("A", 10)
        assertEquals(1, oldValue)
        assertEquals(10, cache.get("A"))
    }

    @Test
    fun `remove deletes entry and returns old value`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        val removed = cache.remove("A")
        assertEquals(1, removed)
        assertNull(cache.get("A"))
    }

    @Test
    fun `remove returns null for non-existent key`() {
        val cache = LRUCache<String, Int>(3)
        assertNull(cache.remove("nonexistent"))
    }

    @Test
    fun `clear removes all entries`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("A"))
    }

    @Test
    fun `size returns correct count`() {
        val cache = LRUCache<String, Int>(5)
        assertEquals(0, cache.size())
        cache.put("A", 1)
        assertEquals(1, cache.size())
        cache.put("B", 2)
        assertEquals(2, cache.size())
        cache.remove("A")
        assertEquals(1, cache.size())
    }

    @Test
    fun `contains checks key presence`() {
        val cache = LRUCache<String, Int>(3)
        assertFalse(cache.contains("A"))
        cache.put("A", 1)
        assertTrue(cache.contains("A"))
        cache.remove("A")
        assertFalse(cache.contains("A"))
    }

    @Test
    fun `isEmpty returns true when cache is empty`() {
        val cache = LRUCache<String, Int>(3)
        assertTrue(cache.isEmpty())
        cache.put("A", 1)
        assertFalse(cache.isEmpty())
        cache.clear()
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `evicts least recently used when exceeding maxSize`() {
        val cache = LRUCache<String, Int>(2)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3) // Should evict A
        assertNull(cache.get("A"))
        assertEquals(2, cache.get("B"))
        assertEquals(3, cache.get("C"))
    }

    @Test
    fun `get updates access order`() {
        val cache = LRUCache<String, Int>(2)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.get("A") // Makes A most recently used
        cache.put("C", 3) // Should evict B, not A
        assertEquals(1, cache.get("A"))
        assertNull(cache.get("B"))
        assertEquals(3, cache.get("C"))
    }

    @Test
    fun `keys returns all keys in access order`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        assertEquals(listOf("A", "B", "C"), cache.keys())
        cache.get("A") // Move A to end
        assertEquals(listOf("B", "C", "A"), cache.keys())
    }

    @Test
    fun `snapshot returns immutable copy of cache contents`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        val snapshot = cache.snapshot()
        assertEquals(mapOf("A" to 1, "B" to 2), snapshot)

        // Verify snapshot is independent
        cache.put("C", 3)
        assertEquals(2, snapshot.size)
        assertEquals(3, cache.size())
    }

    @Test
    fun `snapshot preserves access order`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        cache.get("A") // Move A to end
        val snapshot = cache.snapshot()
        assertEquals(listOf("B", "C", "A"), snapshot.keys.toList())
    }

    @Test
    fun `handles null values`() {
        val cache = LRUCache<String, String?>(3)
        cache.put("A", null)
        assertTrue(cache.contains("A"))
        assertNull(cache.get("A"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `maxSize of 1 works correctly`() {
        val cache = LRUCache<String, Int>(1)
        cache.put("A", 1)
        assertEquals(1, cache.get("A"))
        cache.put("B", 2)
        assertNull(cache.get("A"))
        assertEquals(2, cache.get("B"))
    }

    @Test
    fun `put returns null when adding new key`() {
        val cache = LRUCache<String, Int>(3)
        val oldValue = cache.put("A", 1)
        assertNull(oldValue)
    }

    @Test
    fun `multiple operations maintain correct state`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)
        cache.get("A")
        cache.put("D", 4) // Evicts B
        cache.remove("C")

        assertEquals(2, cache.size())
        assertEquals(1, cache.get("A"))
        assertNull(cache.get("B"))
        assertNull(cache.get("C"))
        assertEquals(4, cache.get("D"))
    }
}
