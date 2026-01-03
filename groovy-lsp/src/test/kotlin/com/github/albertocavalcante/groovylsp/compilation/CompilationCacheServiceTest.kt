package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilationCacheServiceTest {
    private lateinit var cacheService: CompilationCacheService
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setup() {
        cacheService = CompilationCacheService(testDispatcher)
    }

    @Test
    fun `getCached returns null on cache miss`() {
        val uri = URI.create("file:///test.groovy")
        val result = cacheService.getCached(uri, "test content")

        assertNull(result)
    }

    @Test
    fun `getCached returns ParseResult on hit with matching content`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, content, parseResult)
        val result = cacheService.getCached(uri, content)

        assertNotNull(result)
        assertEquals(parseResult, result)
    }

    @Test
    fun `getCached returns null on content mismatch`() {
        val uri = URI.create("file:///test.groovy")
        val originalContent = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, originalContent, parseResult)
        val result = cacheService.getCached(uri, "class Modified {}")

        assertNull(result)
    }

    @Test
    fun `getCached without content validation returns cached result`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, content, parseResult)
        val result = cacheService.getCached(uri)

        assertNotNull(result)
        assertEquals(parseResult, result)
    }

    @Test
    fun `getCachedWithContent returns content and ParseResult pair`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, content, parseResult)
        val result = cacheService.getCachedWithContent(uri)

        assertNotNull(result)
        assertEquals(content, result.first)
        assertEquals(parseResult, result.second)
    }

    @Test
    fun `putCached stores result in cache`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, content, parseResult)
        val result = cacheService.getCached(uri, content)

        assertNotNull(result)
    }

    @Test
    fun `invalidate removes entry from cache`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, content, parseResult)
        cacheService.invalidate(uri)
        val result = cacheService.getCached(uri, content)

        assertNull(result)
    }

    @Test
    fun `clear removes all entries from cache`() {
        val uri1 = URI.create("file:///test1.groovy")
        val uri2 = URI.create("file:///test2.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri1, content, parseResult)
        cacheService.putCached(uri2, content, parseResult)
        cacheService.clear()

        assertNull(cacheService.getCached(uri1, content))
        assertNull(cacheService.getCached(uri2, content))
    }

    @Test
    fun `trackCompilation stores deferred job`() {
        val uri = URI.create("file:///test.groovy")
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.trackCompilation(uri, deferred)
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
    fun `removeCompilation removes job from tracking`() {
        val uri = URI.create("file:///test.groovy")
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.trackCompilation(uri, deferred)
        cacheService.removeCompilation(uri)
        val result = cacheService.getActiveCompilation(uri)

        assertNull(result)
    }

    @Test
    fun `invalidate removes both cache and compilation job`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.putCached(uri, content, parseResult)
        cacheService.trackCompilation(uri, deferred)
        cacheService.invalidate(uri)

        assertNull(cacheService.getCached(uri, content))
        assertNull(cacheService.getActiveCompilation(uri))
    }

    @Test
    fun `clear removes both cache and compilation jobs`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()
        val deferred = CompletableDeferred<CompilationResult>()

        cacheService.putCached(uri, content, parseResult)
        cacheService.trackCompilation(uri, deferred)
        cacheService.clear()

        assertNull(cacheService.getCached(uri, content))
        assertNull(cacheService.getActiveCompilation(uri))
    }

    @Test
    fun `getStatistics returns cache metrics`() {
        val uri = URI.create("file:///test.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri, content, parseResult)
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

        cacheService.trackCompilation(uri, deferred)
        val stats = cacheService.getStatistics()

        assertEquals(1, stats["activeCompilations"])
    }

    @Test
    fun `getCachedUris returns all cached URIs`() {
        val uri1 = URI.create("file:///test1.groovy")
        val uri2 = URI.create("file:///test2.groovy")
        val content = "class Test {}"
        val parseResult = createMockParseResult()

        cacheService.putCached(uri1, content, parseResult)
        cacheService.putCached(uri2, content, parseResult)
        val uris = cacheService.getCachedUris()

        assertEquals(2, uris.size)
        assertTrue(uri1 in uris)
        assertTrue(uri2 in uris)
    }

    // Helper methods

    private fun createMockParseResult(): ParseResult {
        val moduleNode = mockk<ModuleNode>(relaxed = true)
        val astModel = mockk<GroovyAstModel>(relaxed = true)

        return ParseResult(
            ast = moduleNode,
            astModel = astModel,
            diagnostics = emptyList(),
            isSuccessful = true,
            symbolTable = mockk(relaxed = true),
            tokenIndex = mockk(relaxed = true),
        )
    }
}
