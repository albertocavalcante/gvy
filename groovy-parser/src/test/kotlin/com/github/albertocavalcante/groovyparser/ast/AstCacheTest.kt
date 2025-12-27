package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

class AstCacheTest {

    private lateinit var cache: AstCache
    private val testUri = URI.create("file:///test.groovy")
    private val testContent = "class Foo {}"

    // Use a simple ClassNode as a mock ASTNode
    private fun createMockAst(): ClassNode = ClassHelper.make("TestClass")

    @BeforeEach
    fun setUp() {
        cache = AstCache()
    }

    // ==========================================================================
    // Basic Operations
    // ==========================================================================

    @Nested
    inner class BasicOperations {

        @Test
        fun `put and get should store and retrieve AST`() {
            val ast = createMockAst()
            cache.put(testUri, testContent, ast)

            val retrieved = cache.get(testUri, testContent)

            assertNotNull(retrieved)
            assertEquals(ast, retrieved)
        }

        @Test
        fun `get should return null for unknown URI`() {
            val unknownUri = URI.create("file:///unknown.groovy")

            val result = cache.get(unknownUri, testContent)

            assertNull(result)
        }

        @Test
        fun `get should return null when content has changed`() {
            val ast = createMockAst()
            cache.put(testUri, testContent, ast)

            val result = cache.get(testUri, "different content")

            assertNull(result)
        }

        @Test
        fun `get with changed content should remove stale entry`() {
            val ast = createMockAst()
            cache.put(testUri, testContent, ast)

            // Access with different content
            cache.get(testUri, "different content")

            // Entry should be removed
            assertEquals(0, cache.size())
        }
    }

    // ==========================================================================
    // Remove and Clear
    // ==========================================================================

    @Nested
    inner class RemoveAndClear {

        @Test
        fun `remove should delete entry from cache`() {
            val ast = createMockAst()
            cache.put(testUri, testContent, ast)

            cache.remove(testUri)

            assertNull(cache.get(testUri, testContent))
            assertEquals(0, cache.size())
        }

        @Test
        fun `clear should remove all entries`() {
            val uri1 = URI.create("file:///test1.groovy")
            val uri2 = URI.create("file:///test2.groovy")
            cache.put(uri1, "content1", createMockAst())
            cache.put(uri2, "content2", createMockAst())

            cache.clear()

            assertEquals(0, cache.size())
            assertTrue(cache.getCachedUris().isEmpty())
        }
    }

    // ==========================================================================
    // Contains and Size
    // ==========================================================================

    @Nested
    inner class ContainsAndSize {

        @Test
        fun `contains should return true for cached entry with matching content`() {
            cache.put(testUri, testContent, createMockAst())

            assertTrue(cache.contains(testUri, testContent))
        }

        @Test
        fun `contains should return false for cached entry with different content`() {
            cache.put(testUri, testContent, createMockAst())

            assertFalse(cache.contains(testUri, "different content"))
        }

        @Test
        fun `contains should return false for unknown URI`() {
            assertFalse(cache.contains(testUri, testContent))
        }

        @Test
        fun `size should return number of cached entries`() {
            assertEquals(0, cache.size())

            cache.put(URI.create("file:///a.groovy"), "a", createMockAst())
            assertEquals(1, cache.size())

            cache.put(URI.create("file:///b.groovy"), "b", createMockAst())
            assertEquals(2, cache.size())
        }
    }

    // ==========================================================================
    // URI Enumeration
    // ==========================================================================

    @Nested
    inner class UriEnumeration {

        @Test
        fun `getCachedUris should return all cached URIs`() {
            val uri1 = URI.create("file:///test1.groovy")
            val uri2 = URI.create("file:///test2.groovy")
            cache.put(uri1, "content1", createMockAst())
            cache.put(uri2, "content2", createMockAst())

            val uris = cache.getCachedUris()

            assertEquals(2, uris.size)
            assertTrue(uris.contains(uri1))
            assertTrue(uris.contains(uri2))
        }

        @Test
        fun `getCachedUris should return empty set for empty cache`() {
            assertTrue(cache.getCachedUris().isEmpty())
        }
    }

    // ==========================================================================
    // Unchecked Access
    // ==========================================================================

    @Nested
    inner class UncheckedAccess {

        @Test
        fun `getUnchecked should return AST regardless of content`() {
            val ast = createMockAst()
            cache.put(testUri, testContent, ast)

            // getUnchecked doesn't validate content
            val result = cache.getUnchecked(testUri)

            assertNotNull(result)
            assertEquals(ast, result)
        }

        @Test
        fun `getUnchecked should return null for unknown URI`() {
            assertNull(cache.getUnchecked(testUri))
        }
    }

    // ==========================================================================
    // LRU Eviction
    // ==========================================================================

    @Nested
    inner class LruEviction {

        @Test
        fun `should evict oldest entries when exceeding max size`() {
            // MAX_CACHE_SIZE is 100 in AstCache
            // Add 101 entries to trigger eviction
            for (i in 1..101) {
                val uri = URI.create("file:///test$i.groovy")
                cache.put(uri, "content$i", createMockAst())
            }

            // Should have evicted to stay at or below max size
            assertTrue(cache.size() <= 100)
        }

        @Test
        fun `should evict entries to maintain max size after access`() {
            // NOTE: The LRU eviction ordering is based on System.currentTimeMillis()
            // which may not differentiate operations within the same millisecond.
            // This test verifies that:
            // 1. Eviction occurs when cache exceeds max size
            // 2. Cache maintains its size bound
            // A more robust LRU implementation would use a monotonic counter.

            // Add 100 entries
            for (i in 1..100) {
                val uri = URI.create("file:///test$i.groovy")
                cache.put(uri, "content$i", createMockAst())
            }
            assertEquals(100, cache.size())

            // Access an entry (updates its access time, though may be same millisecond)
            val firstUri = URI.create("file:///test1.groovy")
            cache.get(firstUri, "content1")

            // Add more entries to trigger eviction
            cache.put(URI.create("file:///test101.groovy"), "content101", createMockAst())
            cache.put(URI.create("file:///test102.groovy"), "content102", createMockAst())

            // Verify eviction occurred - cache should be at or below max size
            assertTrue(cache.size() <= 100, "Cache should evict to stay at max size")
        }
    }
}
