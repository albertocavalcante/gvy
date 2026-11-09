package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilationCacheServiceTest {
    private companion object {
        const val FP = "test-fingerprint"
    }

    private lateinit var cacheService: CompilationCacheService
    private val identity = CompilationIdentity("class Test {}", "test-fingerprint", null)

    @BeforeTest
    fun setup() {
        cacheService = CompilationCacheService()
    }

    @Test
    fun `getCached returns null on cache miss`() {
        val uri = URI.create("file:///test.groovy")
        val result = cacheService.getCached(uri, "test content", FP)

        assertNull(result)
    }

    @Test
    fun `getCached returns ParseResult on hit with matching content`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri, content, parseResult)
        val result = cacheService.getCached(uri, content, FP)

        assertNotNull(result)
        assertEquals(parseResult, result)
    }

    @Test
    fun `getCached returns null on content mismatch`() {
        val uri = URI.create("file:///test.groovy")
        val originalContent = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri, originalContent, parseResult)
        val result = cacheService.getCached(uri, "class Modified {}", FP)

        assertNull(result)
    }

    @Test
    fun `content-less read rejects an entry from another configuration`() {
        val uri = URI.create("file:///test.groovy")
        put(uri, "class Test {}", createMockParseResult())

        assertNull(cacheService.getCachedWithContent(uri, "other-fingerprint"))
    }

    @Test
    fun `publish with a retired ticket stores nothing`() {
        val uri = URI.create("file:///test.groovy")
        val ticket = cacheService.ownership.begin(uri)
        var alongside = false
        cacheService.ownership.retire(uri)

        val stored = cacheService.publish(ticket, "class Test {}", createMockParseResult(), FP) { alongside = true }

        assertFalse(stored)
        assertFalse(alongside, "the other artifacts of a retired compile must not be written either")
        assertNull(cacheService.getCachedWithContent(uri, FP))
    }

    @Test
    fun `retiring one file does not retire another`() {
        val uri = URI.create("file:///test.groovy")
        val other = URI.create("file:///other.groovy")
        val ticket = cacheService.ownership.begin(uri)
        cacheService.ownership.retire(other)

        assertTrue(cacheService.publish(ticket, "class Test {}", createMockParseResult(), FP))
    }

    @Test
    fun `getCachedWithContent returns content and ParseResult pair`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri, content, parseResult)
        val result = cacheService.getCachedWithContent(uri, FP)

        assertNotNull(result)
        assertEquals(content, result.first)
        assertEquals(parseResult, result.second)
    }

    @Test
    fun `putCached stores result in cache`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri, content, parseResult)
        val result = cacheService.getCached(uri, content, FP)

        assertNotNull(result)
    }

    @Test
    fun `invalidate removes entry from cache`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri, content, parseResult)
        cacheService.invalidate(uri)
        val result = cacheService.getCached(uri, content, FP)

        assertNull(result)
    }

    @Test
    fun `clear removes all entries from cache`() {
        val uri1 = URI.create("file:///test1.groovy")
        val uri2 = URI.create("file:///test2.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri1, content, parseResult)
        put(uri2, content, parseResult)
        cacheService.clear()

        assertNull(cacheService.getCached(uri1, content, FP))
        assertNull(cacheService.getCached(uri2, content, FP))
    }

    @Test
    fun `registerCompilation stores deferred job`() {
        val uri = URI.create("file:///test.groovy")
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.registerCompilation(uri, identity) { deferred }
        val result = cacheService.getActiveCompilation(uri)

        assertNotNull(result)
        assertEquals(deferred, result)
    }

    @Test
    fun `getActiveCompilation returns null for unknown URI`() {
        val uri = URI.create("file:///test.groovy")
        val result = cacheService.getActiveCompilation(uri)

        assertNull(result)
    }

    @Test
    fun `completion removes its job from tracking`() {
        val uri = URI.create("file:///test.groovy")
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.registerCompilation(uri, identity) { deferred }
        deferred.complete(CompilationResult.failure(emptyList()))
        val result = cacheService.getActiveCompilation(uri)

        assertNull(result)
    }

    @Test
    fun `invalidate removes both cache and compilation job`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()
        val deferred = CompletableDeferred<CompilationResult>()

        put(uri, content, parseResult)
        cacheService.registerCompilation(uri, identity) { deferred }
        cacheService.invalidate(uri)

        assertNull(cacheService.getCached(uri, content, FP))
        assertNull(cacheService.getActiveCompilation(uri))
        assertTrue(deferred.isCancelled, "an invalidated job must be cancelled, not only forgotten")
    }

    @Test
    fun `clear removes both cache and compilation jobs`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()
        val deferred = CompletableDeferred<CompilationResult>()

        put(uri, content, parseResult)
        cacheService.registerCompilation(uri, identity) { deferred }
        cacheService.clear()

        assertNull(cacheService.getCached(uri, content, FP))
        assertNull(cacheService.getActiveCompilation(uri))
        assertTrue(deferred.isCancelled, "a cleared job must be cancelled, not only forgotten")
    }

    @Test
    fun `getStatistics returns cache metrics`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri, content, parseResult)
        val stats = cacheService.getStatistics()

        assertTrue(stats.containsKey("cachedResults"))
        assertTrue(stats.containsKey("activeCompilations"))
        assertEquals(1, stats["cachedResults"])
        assertEquals(0, stats["activeCompilations"])
    }

    @Test
    fun `getStatistics includes active compilations count`() {
        val uri = URI.create("file:///test.groovy")
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.registerCompilation(uri, identity) { deferred }
        val stats = cacheService.getStatistics()

        assertEquals(1, stats["activeCompilations"])
    }

    @Test
    fun `getCachedUris returns all cached URIs`() {
        val uri1 = URI.create("file:///test1.groovy")
        val uri2 = URI.create("file:///test2.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        put(uri1, content, parseResult)
        put(uri2, content, parseResult)
        val uris = cacheService.getCachedUris()

        assertEquals(2, uris.size)
        assertTrue(uri1 in uris)
        assertTrue(uri2 in uris)
    }

    // Helper methods

    private fun put(uri: URI, content: String, parseResult: ParseResult) {
        assertTrue(cacheService.publish(cacheService.ownership.begin(uri), content, parseResult, FP))
    }

    private fun createMockParseResult(): ParseResult {
        val moduleNode = mockk<ModuleNode>(relaxed = true)
        val astModel = mockk<GroovyAstModel>(relaxed = true)

        return ParseResult(
            ast = moduleNode,
            astModel = astModel,
            diagnostics = emptyList(),
            symbolTable = mockk(relaxed = true),
            tokenIndex = mockk(relaxed = true),
            compilationUnit = mockk(relaxed = true),
            sourceUnit = mockk(relaxed = true),
        )
    }
}
