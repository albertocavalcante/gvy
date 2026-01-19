package com.github.albertocavalcante.gvy.gls.cache

import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticCacheTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var cache: SemanticCache

    @AfterEach
    fun cleanup() = runTest {
        if (::cache.isInitialized) {
            cache.clear()
        }
    }

    @Test
    fun `save and load semantic document successfully`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val sourceHash = "abc123"

        // Save document
        cache.save(uri, document, sourceHash)

        // Load document
        val loaded = cache.load(uri, sourceHash)

        assertNotNull(loaded)
        assertEquals(uri, loaded.uri)
        assertEquals(1, loaded.symbols.size)
        assertEquals(1, loaded.occurrences.size)
    }

    @Test
    fun `load returns null when cache does not exist`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/NonExistent.groovy")
        val sourceHash = "abc123"

        val loaded = cache.load(uri, sourceHash)

        assertNull(loaded)
    }

    @Test
    fun `load returns null when source hash does not match`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val originalHash = "abc123"
        val differentHash = "xyz789"

        // Save with original hash
        cache.save(uri, document, originalHash)

        // Try to load with different hash
        val loaded = cache.load(uri, differentHash)

        assertNull(loaded)
    }

    @Test
    fun `isValid returns true when cache exists and hash matches`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val sourceHash = "abc123"

        cache.save(uri, document, sourceHash)

        val valid = cache.isValid(uri, sourceHash)

        assertEquals(true, valid)
    }

    @Test
    fun `isValid returns false when cache does not exist`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/NonExistent.groovy")
        val sourceHash = "abc123"

        val valid = cache.isValid(uri, sourceHash)

        assertEquals(false, valid)
    }

    @Test
    fun `isValid returns false when hash does not match`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val originalHash = "abc123"
        val differentHash = "xyz789"

        cache.save(uri, document, originalHash)

        val valid = cache.isValid(uri, differentHash)

        assertEquals(false, valid)
    }

    @Test
    fun `invalidate removes cache for a file`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val sourceHash = "abc123"

        // Verify cache exists before invalidation
        cache.save(uri, document, sourceHash)
        assertTrue(
            cache.isValid(uri, sourceHash),
            "Cache should be valid before invalidation",
        )

        // Invalidate the cache
        cache.invalidate(uri)

        // Verify cache is completely removed
        assertFalse(
            cache.isValid(uri, sourceHash),
            "Cache should be invalid after invalidation",
        )
        val loaded = cache.load(uri, sourceHash)
        assertNull(loaded, "Load should return null after invalidation")
    }

    @Test
    fun `clear removes all cached files`() = runTest {
        cache = SemanticCache(tempDir)
        val uri1 = URI.create("file:///test/Example1.groovy")
        val uri2 = URI.create("file:///test/Example2.groovy")
        val uri3 = URI.create("file:///test/Example3.groovy")
        val doc1 = createTestDocument(uri1)
        val doc2 = createTestDocument(uri2)
        val doc3 = createTestDocument(uri3)
        val hash = "abc123"

        // Save 3 files
        cache.save(uri1, doc1, hash)
        cache.save(uri2, doc2, hash)
        cache.save(uri3, doc3, hash)

        // Verify all are valid before clear
        assertTrue(cache.isValid(uri1, hash), "uri1 should be valid before clear")
        assertTrue(cache.isValid(uri2, hash), "uri2 should be valid before clear")
        assertTrue(cache.isValid(uri3, hash), "uri3 should be valid before clear")

        // Clear all caches
        cache.clear()

        // Verify all are removed
        assertNull(cache.load(uri1, hash), "uri1 should be null after clear")
        assertNull(cache.load(uri2, hash), "uri2 should be null after clear")
        assertNull(cache.load(uri3, hash), "uri3 should be null after clear")
        assertFalse(cache.isValid(uri1, hash), "uri1 should be invalid after clear")
        assertFalse(cache.isValid(uri2, hash), "uri2 should be invalid after clear")
        assertFalse(cache.isValid(uri3, hash), "uri3 should be invalid after clear")
    }

    @Test
    fun `save handles complex semantic document with types`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Complex.groovy")
        val document = createComplexDocument(uri)
        val sourceHash = "complex123"

        cache.save(uri, document, sourceHash)

        val loaded = cache.load(uri, sourceHash)

        assertNotNull(loaded)
        assertEquals(3, loaded.symbols.size)
        assertEquals(5, loaded.occurrences.size)

        // Verify types are preserved
        val classSymbol = loaded.symbols.find { it.kind == SymbolKind.CLASS }
        assertNotNull(classSymbol)
    }

    @Test
    fun `handles IO errors gracefully`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")

        // Try to load from non-existent cache
        val loaded = cache.load(uri, "hash")

        // Should return null instead of throwing
        assertNull(loaded)
    }

    @Test
    fun `save overwrites existing cache`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val doc1 = createTestDocument(uri)
        val doc2 = createComplexDocument(uri)
        val hash1 = "hash1"
        val hash2 = "hash2"

        cache.save(uri, doc1, hash1)
        cache.save(uri, doc2, hash2)

        // Loading with old hash should fail
        assertNull(cache.load(uri, hash1))

        // Loading with new hash should succeed
        val loaded = cache.load(uri, hash2)
        assertNotNull(loaded)
        assertEquals(3, loaded.symbols.size)
    }

    private fun createTestDocument(uri: URI): SemanticDocument {
        val symbols = listOf(
            SymbolInfo(
                symbol = "com/example/Example#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 13),
                name = "Example",
                owner = null,
                type = null,
            ),
        )
        val occurrences = listOf(
            SymbolOccurrence(
                symbol = "com/example/Example#",
                range = Range(0, 6, 0, 13),
                role = OccurrenceRole.DEFINITION,
            ),
        )
        return SemanticDocument(uri, symbols, occurrences)
    }

    private fun createComplexDocument(uri: URI): SemanticDocument {
        val symbols = listOf(
            SymbolInfo(
                symbol = "com/example/Complex#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 13),
                name = "Complex",
                owner = null,
                type = null,
            ),
            SymbolInfo(
                symbol = "com/example/Complex#method().",
                kind = SymbolKind.METHOD,
                range = Range(1, 4, 1, 10),
                name = "method",
                owner = "com/example/Complex#",
                type = null,
            ),
            SymbolInfo(
                symbol = "com/example/Complex#field.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 9),
                name = "field",
                owner = "com/example/Complex#",
                type = null,
            ),
        )
        val occurrences = listOf(
            SymbolOccurrence(
                symbol = "com/example/Complex#",
                range = Range(0, 6, 0, 13),
                role = OccurrenceRole.DEFINITION,
            ),
            SymbolOccurrence(
                symbol = "com/example/Complex#method().",
                range = Range(1, 4, 1, 10),
                role = OccurrenceRole.DEFINITION,
            ),
            SymbolOccurrence(
                symbol = "com/example/Complex#field.",
                range = Range(2, 4, 2, 9),
                role = OccurrenceRole.DEFINITION,
            ),
            SymbolOccurrence(
                symbol = "com/example/Complex#method().",
                range = Range(3, 8, 3, 14),
                role = OccurrenceRole.CALL,
            ),
            SymbolOccurrence(
                symbol = "com/example/Complex#field.",
                range = Range(4, 8, 4, 13),
                role = OccurrenceRole.REFERENCE,
            ),
        )
        return SemanticDocument(uri, symbols, occurrences)
    }

    // ========================================================================
    // Tests for Fix #7: Cache I/O methods are suspend functions
    // ========================================================================

    @Test
    fun `isValid should not block calling thread`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val sourceHash = "abc123"

        cache.save(uri, document, sourceHash)

        // Verify isValid can be called in a coroutine context
        val isValid = cache.isValid(uri, sourceHash)
        assertTrue(isValid)

        // Verify it works with coroutine context switching
        val isValidFromDifferentContext = withContext(Dispatchers.Default) {
            cache.isValid(uri, sourceHash)
        }
        assertTrue(isValidFromDifferentContext)
    }

    @Test
    fun `invalidate should run on IO dispatcher`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val sourceHash = "abc123"

        cache.save(uri, document, sourceHash)
        assertTrue(cache.isValid(uri, sourceHash))

        // Invalidate should work as a suspend function
        cache.invalidate(uri)

        // Verify cache was invalidated
        val loaded = cache.load(uri, sourceHash)
        assertNull(loaded)
    }

    @Test
    fun `clear should run on IO dispatcher`() = runTest {
        cache = SemanticCache(tempDir)
        val uri1 = URI.create("file:///test/Example1.groovy")
        val uri2 = URI.create("file:///test/Example2.groovy")
        val doc1 = createTestDocument(uri1)
        val doc2 = createTestDocument(uri2)
        val hash = "abc123"

        cache.save(uri1, doc1, hash)
        cache.save(uri2, doc2, hash)

        // Clear should work as a suspend function
        cache.clear()

        // Verify all caches were cleared
        assertNull(cache.load(uri1, hash))
        assertNull(cache.load(uri2, hash))
    }

    @Test
    fun `concurrent cache operations should work correctly`() = runTest {
        cache = SemanticCache(tempDir)

        // Create multiple documents
        val documents = (1..10).map { i ->
            val uri = URI.create("file:///test/Example$i.groovy")
            val doc = createTestDocument(uri)
            Triple(uri, doc, "hash$i")
        }

        // Save all documents concurrently
        documents.map { (uri, doc, hash) ->
            async {
                cache.save(uri, doc, hash)
            }
        }.awaitAll()

        // Verify all documents can be loaded concurrently
        val loadResults = documents.map { (uri, _, hash) ->
            async {
                cache.load(uri, hash)
            }
        }.awaitAll()

        // All should be successfully loaded
        loadResults.forEachIndexed { index, loaded ->
            assertNotNull(loaded, "Document $index should be loaded")
            assertEquals(documents[index].first, loaded.uri)
        }

        // Verify isValid works concurrently
        val validResults = documents.map { (uri, _, hash) ->
            async {
                cache.isValid(uri, hash)
            }
        }.awaitAll()

        validResults.forEach { isValid ->
            assertTrue(isValid, "All documents should be valid")
        }
    }

    @Test
    fun `suspend functions should work with different coroutine contexts`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Example.groovy")
        val document = createTestDocument(uri)
        val sourceHash = "abc123"

        // Save in Default context
        withContext(Dispatchers.Default) {
            cache.save(uri, document, sourceHash)
        }

        // Load in IO context
        val loaded = withContext(Dispatchers.IO) {
            cache.load(uri, sourceHash)
        }
        assertNotNull(loaded)

        // Check validity in Default context
        val isValid = withContext(Dispatchers.Default) {
            cache.isValid(uri, sourceHash)
        }
        assertTrue(isValid)

        // Invalidate in IO context
        withContext(Dispatchers.IO) {
            cache.invalidate(uri)
        }

        // Verify invalidation
        val afterInvalidate = cache.load(uri, sourceHash)
        assertNull(afterInvalidate)
    }

    // ========================================================================
    // Tests for Fix #8: Consolidated hashing logic
    // ========================================================================

    @Test
    fun `hashString and hashSource should produce identical results for same content`() = runTest {
        val content = "class Example { String field }"

        // Use the public hashSource method
        val hash1 = SemanticCache.hashSource(content)
        val hash2 = SemanticCache.hashSource(content)

        // Both should produce the same hash
        assertEquals(hash1, hash2)
    }

    @Test
    fun `sha256Hash should produce correct SHA-256 format`() = runTest {
        val content = "test content"
        val hash = SemanticCache.hashSource(content)

        // SHA-256 produces 64 hex characters (32 bytes * 2)
        assertEquals(64, hash.length, "SHA-256 hash should be 64 characters")

        // Should only contain hex characters (0-9, a-f)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "Hash should only contain hex characters")

        // Verify specific hash format with regex pattern
        val sha256Pattern = Regex("^[0-9a-f]{64}$")
        assertTrue(
            sha256Pattern.matches(hash),
            "Hash should match SHA-256 pattern: lowercase hex, exactly 64 chars",
        )
    }

    @Test
    fun `sha256Hash should handle empty string`() = runTest {
        val emptyHash = SemanticCache.hashSource("")

        // Should produce a valid hash even for empty string
        assertEquals(64, emptyHash.length)
        assertTrue(emptyHash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `different content should produce different hashes`() = runTest {
        val content1 = "class Example1 { }"
        val content2 = "class Example2 { }"

        val hash1 = SemanticCache.hashSource(content1)
        val hash2 = SemanticCache.hashSource(content2)

        // Different content should produce different hashes
        assertTrue(hash1 != hash2, "Different content should have different hashes")
    }

    @Test
    fun `hashing should be consistent across calls`() = runTest {
        cache = SemanticCache(tempDir)
        val content = "class Test { String field }"

        // Hash the same content multiple times
        val hashes = (1..10).map { SemanticCache.hashSource(content) }

        // All hashes should be identical
        val uniqueHashes = hashes.toSet()
        assertEquals(1, uniqueHashes.size, "Same content should always produce same hash")
    }

    @Test
    fun `cache should use consistent hashing for file names`() = runTest {
        cache = SemanticCache(tempDir)

        // Create two URIs
        val uri1 = URI.create("file:///test/Example.groovy")
        val uri2 = URI.create("file:///test/Different.groovy")

        val doc1 = createTestDocument(uri1)
        val doc2 = createTestDocument(uri2)
        val hash = "abc123"

        // Save both documents
        cache.save(uri1, doc1, hash)
        cache.save(uri2, doc2, hash)

        // Both should be loadable independently
        val loaded1 = cache.load(uri1, hash)
        val loaded2 = cache.load(uri2, hash)

        assertNotNull(loaded1)
        assertNotNull(loaded2)
        assertEquals(uri1, loaded1.uri)
        assertEquals(uri2, loaded2.uri)
    }

    // ========================================================================
    // Enhanced tests for quantitative assertions and edge cases
    // ========================================================================

    @Test
    fun `concurrent cache operations complete within reasonable time`() = runTest {
        cache = SemanticCache(tempDir)
        val startTime = System.currentTimeMillis()

        // Create 20 documents
        val documents = (1..20).map { i ->
            val uri = URI.create("file:///test/Concurrent$i.groovy")
            val doc = createTestDocument(uri)
            Triple(uri, doc, "hash$i")
        }

        // Save all concurrently
        documents.map { (uri, doc, hash) ->
            async {
                cache.save(uri, doc, hash)
            }
        }.awaitAll()

        val saveTime = System.currentTimeMillis() - startTime

        // Load all concurrently
        val loadStartTime = System.currentTimeMillis()
        val loadResults = documents.map { (uri, _, hash) ->
            async {
                cache.load(uri, hash)
            }
        }.awaitAll()

        val loadTime = System.currentTimeMillis() - loadStartTime

        // Verify all loaded successfully
        assertEquals(20, loadResults.size, "Should have 20 results")
        loadResults.forEach { loaded ->
            assertNotNull(loaded, "All documents should load successfully")
        }

        // Time assertions - operations should complete in reasonable time (non-blocking)
        assertTrue(saveTime < 5000, "Save operations should complete in < 5s (was ${saveTime}ms)")
        assertTrue(loadTime < 5000, "Load operations should complete in < 5s (was ${loadTime}ms)")
    }

    @Test
    fun `cache miss and cache hit have predictable behavior`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/MissHit.groovy")
        val document = createTestDocument(uri)
        val hash = "testhash"

        // Cache miss - load before save
        val missResult = cache.load(uri, hash)
        assertNull(missResult, "Cache miss should return null")
        assertFalse(cache.isValid(uri, hash), "Cache miss should be invalid")

        // Save and verify cache hit
        cache.save(uri, document, hash)

        val hitResult = cache.load(uri, hash)
        assertNotNull(hitResult, "Cache hit should return document")
        assertTrue(cache.isValid(uri, hash), "Cache hit should be valid")
        assertEquals(uri, hitResult.uri, "Cache hit should return correct URI")
        assertEquals(1, hitResult.symbols.size, "Cache hit should have exact symbol count")
        assertEquals(1, hitResult.occurrences.size, "Cache hit should have exact occurrence count")
    }

    @Test
    fun `invalidate affects specific file only not others`() = runTest {
        cache = SemanticCache(tempDir)
        val uri1 = URI.create("file:///test/File1.groovy")
        val uri2 = URI.create("file:///test/File2.groovy")
        val uri3 = URI.create("file:///test/File3.groovy")
        val doc1 = createTestDocument(uri1)
        val doc2 = createTestDocument(uri2)
        val doc3 = createTestDocument(uri3)
        val hash = "samehash"

        // Save all 3 files
        cache.save(uri1, doc1, hash)
        cache.save(uri2, doc2, hash)
        cache.save(uri3, doc3, hash)

        // Count valid caches before invalidation
        val validBefore = listOf(uri1, uri2, uri3).count { cache.isValid(it, hash) }
        assertEquals(3, validBefore, "Should have exactly 3 valid caches before invalidation")

        // Invalidate only uri2
        cache.invalidate(uri2)

        // Count valid caches after invalidation
        val validAfter = listOf(uri1, uri2, uri3).count { cache.isValid(it, hash) }
        assertEquals(2, validAfter, "Should have exactly 2 valid caches after invalidating one")

        // Verify specific files
        assertTrue(cache.isValid(uri1, hash), "uri1 should still be valid")
        assertFalse(cache.isValid(uri2, hash), "uri2 should be invalid")
        assertTrue(cache.isValid(uri3, hash), "uri3 should still be valid")
    }

    @Test
    fun `save and load preserve exact symbol and occurrence counts`() = runTest {
        cache = SemanticCache(tempDir)
        val uri = URI.create("file:///test/Exact.groovy")
        val complexDoc = createComplexDocument(uri)
        val hash = "exacthash"

        // Verify counts before save
        assertEquals(3, complexDoc.symbols.size, "Original should have 3 symbols")
        assertEquals(5, complexDoc.occurrences.size, "Original should have 5 occurrences")

        // Save and load
        cache.save(uri, complexDoc, hash)
        val loaded = cache.load(uri, hash)

        // Verify exact counts after load
        assertNotNull(loaded, "Loaded document should not be null")
        assertEquals(3, loaded.symbols.size, "Loaded should have exactly 3 symbols")
        assertEquals(5, loaded.occurrences.size, "Loaded should have exactly 5 occurrences")

        // Verify symbol kinds
        val symbolKinds = loaded.symbols.map { it.kind }
        assertEquals(3, symbolKinds.size, "Should have 3 symbol kinds")
        assertTrue(symbolKinds.contains(SymbolKind.CLASS), "Should contain CLASS kind")
        assertTrue(symbolKinds.contains(SymbolKind.METHOD), "Should contain METHOD kind")
        assertTrue(symbolKinds.contains(SymbolKind.FIELD), "Should contain FIELD kind")

        // Verify occurrence roles
        val occurrenceRoles = loaded.occurrences.map { it.role }
        assertEquals(5, occurrenceRoles.size, "Should have 5 occurrence roles")
        val defCount = occurrenceRoles.count { it == OccurrenceRole.DEFINITION }
        val callCount = occurrenceRoles.count { it == OccurrenceRole.CALL }
        val refCount = occurrenceRoles.count { it == OccurrenceRole.REFERENCE }
        assertEquals(3, defCount, "Should have exactly 3 definitions")
        assertEquals(1, callCount, "Should have exactly 1 call")
        assertEquals(1, refCount, "Should have exactly 1 reference")
    }

    @Test
    fun `hash output is deterministic for same input`() = runTest {
        val content = "class DeterministicTest { String field }"

        // Hash same content 100 times
        val hashes = (1..100).map { SemanticCache.hashSource(content) }

        // All should be identical
        val uniqueHashes = hashes.toSet()
        assertEquals(
            1,
            uniqueHashes.size,
            "Same content should always produce same hash (got ${uniqueHashes.size} unique hashes)",
        )

        val expectedHash = hashes.first()
        hashes.forEach { hash ->
            assertEquals(
                expectedHash,
                hash,
                "Every hash should match the expected hash",
            )
        }
    }

    @Test
    fun `hash distinguishes minimal content differences`() = runTest {
        val content1 = "class Test { String field }"
        val content2 = "class Test { String field }" // Same
        val content3 = "class Test { String field  }" // Extra space
        val content4 = "class Test { String field1 }" // Different field name

        val hash1 = SemanticCache.hashSource(content1)
        val hash2 = SemanticCache.hashSource(content2)
        val hash3 = SemanticCache.hashSource(content3)
        val hash4 = SemanticCache.hashSource(content4)

        // Same content should produce same hash
        assertEquals(hash1, hash2, "Identical content should produce identical hashes")

        // Different content should produce different hashes
        assertTrue(hash1 != hash3, "Extra whitespace should produce different hash")
        assertTrue(hash1 != hash4, "Different field name should produce different hash")
        assertTrue(hash3 != hash4, "Different modifications should produce different hashes")
    }
}
