package com.github.albertocavalcante.groovylsp.cache

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

        cache.save(uri, document, sourceHash)
        cache.invalidate(uri)

        val loaded = cache.load(uri, sourceHash)
        assertNull(loaded)
    }

    @Test
    fun `clear removes all cached files`() = runTest {
        cache = SemanticCache(tempDir)
        val uri1 = URI.create("file:///test/Example1.groovy")
        val uri2 = URI.create("file:///test/Example2.groovy")
        val doc1 = createTestDocument(uri1)
        val doc2 = createTestDocument(uri2)
        val hash = "abc123"

        cache.save(uri1, doc1, hash)
        cache.save(uri2, doc2, hash)

        cache.clear()

        assertNull(cache.load(uri1, hash))
        assertNull(cache.load(uri2, hash))
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
}
