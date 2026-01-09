package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for CompilationCache - ensures cache coherency through
 * configuration fingerprint validation (Issue #743).
 */
class CompilationCacheTest {

    private val uri = URI.create("file:///Test.groovy")
    private val content = "class Test {}"
    private val parseResult = mockk<ParseResult>(relaxed = true)

    @Test
    fun `get returns cached result when content and fingerprint match`() {
        val cache = CompilationCache()
        val fingerprint = "abc123"

        cache.put(uri, content, parseResult, fingerprint)

        val result = cache.get(uri, content, fingerprint)

        assertNotNull(result)
        assertEquals(parseResult, result)
    }

    @Test
    fun `get returns null when content differs`() {
        val cache = CompilationCache()
        val fingerprint = "abc123"

        cache.put(uri, content, parseResult, fingerprint)

        val result = cache.get(uri, "class Modified {}", fingerprint)

        assertNull(result)
    }

    @Test
    fun `get returns null when fingerprint differs`() {
        val cache = CompilationCache()

        cache.put(uri, content, parseResult, "fingerprint-v1")

        val result = cache.get(uri, content, "fingerprint-v2")

        assertNull(result, "Cache should return null when configuration fingerprint changes")
    }

    @Test
    fun `get without fingerprint ignores fingerprint validation`() {
        val cache = CompilationCache()

        cache.put(uri, content, parseResult, "any-fingerprint")

        // Old API without fingerprint should still work for backward compatibility
        val result = cache.get(uri, content)

        assertNotNull(result)
    }

    @Test
    fun `cache invalidation removes entry regardless of fingerprint`() {
        val cache = CompilationCache()

        cache.put(uri, content, parseResult, "fingerprint")
        cache.invalidate(uri)

        val result = cache.get(uri, content, "fingerprint")

        assertNull(result)
    }

    @Test
    fun `clear removes all entries`() {
        val cache = CompilationCache()
        val uri1 = URI.create("file:///A.groovy")
        val uri2 = URI.create("file:///B.groovy")

        cache.put(uri1, "class A {}", parseResult, "fp1")
        cache.put(uri2, "class B {}", parseResult, "fp2")
        cache.clear()

        assertNull(cache.get(uri1, "class A {}", "fp1"))
        assertNull(cache.get(uri2, "class B {}", "fp2"))
    }

    @Test
    fun `statistics include cached count`() {
        val cache = CompilationCache()

        cache.put(uri, content, parseResult, "fp")

        val stats = cache.getStatistics()

        assertEquals(1, stats["cachedResults"])
    }
}
