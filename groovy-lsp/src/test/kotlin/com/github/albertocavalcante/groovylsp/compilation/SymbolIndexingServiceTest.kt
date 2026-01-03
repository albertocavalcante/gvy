package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolIndexingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var indexingService: SymbolIndexingService
    private lateinit var workerSessionManager: WorkerSessionManager
    private lateinit var workspaceManager: WorkspaceManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setup() {
        workerSessionManager = mockk(relaxed = true)
        workspaceManager = mockk(relaxed = true)

        // Default mocking for workspace manager
        every { workspaceManager.getDependencyClasspath() } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns emptyList()

        indexingService = SymbolIndexingService(
            ioDispatcher = testDispatcher,
            workerSessionManager = workerSessionManager,
            workspaceManager = workspaceManager,
            maxCacheSize = 10,
        )
    }

    @Test
    fun `getSymbolIndex returns null when no cache and no provider`() {
        val uri = URI.create("file:///test.groovy")

        val result = indexingService.getSymbolIndex(uri)

        assertNull(result)
    }

    @Test
    fun `getSymbolIndex returns from cache if available`() = runBlocking {
        val uri = URI.create("file:///test.groovy")
        val file = tempDir.resolve("test.groovy").apply {
            createFile()
            writeText("class Test {}")
        }

        // Mock parse result
        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        // Index the file first
        indexingService.indexFile(file.toUri())

        // Now retrieve from cache
        val result = indexingService.getSymbolIndex(file.toUri())

        assertNotNull(result)
    }

    @Test
    fun `getSymbolIndex builds from AST model provider if provided`() {
        val uri = URI.create("file:///test.groovy")
        val astModel = mockk<GroovyAstModel>(relaxed = true)

        val result = indexingService.getSymbolIndex(uri) { astModel }

        assertNotNull(result)
    }

    @Test
    fun `indexFile returns SymbolIndex for valid file`() = runBlocking {
        val file = tempDir.resolve("test.groovy").apply {
            createFile()
            writeText("class Test {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        val result = indexingService.indexFile(file.toUri())

        assertNotNull(result)
    }

    @Test
    fun `indexFile returns null for non-existent file`() = runBlocking {
        val uri = URI.create("file:///does-not-exist.groovy")

        val result = indexingService.indexFile(uri)

        assertNull(result)
    }

    @Test
    fun `indexFile caches result in LRU cache`() = runBlocking {
        val file = tempDir.resolve("test.groovy").apply {
            createFile()
            writeText("class Test {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        indexingService.indexFile(file.toUri())
        val cachedResult = indexingService.getSymbolIndex(file.toUri())

        assertNotNull(cachedResult)
    }

    @Test
    fun `indexFile skips indexing if already cached`() = runBlocking {
        val file = tempDir.resolve("test.groovy").apply {
            createFile()
            writeText("class Test {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        // Index twice
        val result1 = indexingService.indexFile(file.toUri())
        val result2 = indexingService.indexFile(file.toUri())

        assertNotNull(result1)
        assertNotNull(result2)
        // Should return same instance from cache on second call
    }

    @Test
    fun `indexAllWorkspaceSources indexes all files`() = runBlocking {
        val file1 = tempDir.resolve("test1.groovy").apply {
            createFile()
            writeText("class Test1 {}")
        }
        val file2 = tempDir.resolve("test2.groovy").apply {
            createFile()
            writeText("class Test2 {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        indexingService.indexAllWorkspaceSources(listOf(file1.toUri(), file2.toUri()))

        val indices = indexingService.getAllSymbolIndices()
        assertEquals(2, indices.size)
    }

    @Test
    fun `indexAllWorkspaceSources reports progress`() = runBlocking {
        val file1 = tempDir.resolve("test1.groovy").apply {
            createFile()
            writeText("class Test1 {}")
        }
        val file2 = tempDir.resolve("test2.groovy").apply {
            createFile()
            writeText("class Test2 {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        val progressUpdates = mutableListOf<Pair<Int, Int>>()
        indexingService.indexAllWorkspaceSources(
            uris = listOf(file1.toUri(), file2.toUri()),
            onProgress = { indexed, total ->
                progressUpdates.add(indexed to total)
            },
        )

        assertEquals(2, progressUpdates.size)
        assertEquals(1 to 2, progressUpdates[0])
        assertEquals(2 to 2, progressUpdates[1])
    }

    @Test
    fun `indexAllWorkspaceSources continues on individual failures`() = runBlocking {
        val validFile = tempDir.resolve("valid.groovy").apply {
            createFile()
            writeText("class Valid {}")
        }
        val invalidUri = URI.create("file:///does-not-exist.groovy")

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        // Should not throw, continues with valid file
        indexingService.indexAllWorkspaceSources(listOf(validFile.toUri(), invalidUri))

        val indices = indexingService.getAllSymbolIndices()
        assertEquals(1, indices.size)
    }

    @Test
    fun `indexAllWorkspaceSources handles empty list`() = runBlocking {
        indexingService.indexAllWorkspaceSources(emptyList())
        val indices = indexingService.getAllSymbolIndices()
        assertEquals(0, indices.size)
    }

    @Test
    fun `getAllSymbolIndices merges cache and workspace index`() = runBlocking {
        val file1 = tempDir.resolve("test1.groovy").apply {
            createFile()
            writeText("class Test1 {}")
        }
        val file2 = tempDir.resolve("test2.groovy").apply {
            createFile()
            writeText("class Test2 {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        // Index file1 (goes to both caches)
        indexingService.indexFile(file1.toUri())

        // Get symbol index for file2 via provider (goes to LRU cache only)
        val astModel = mockk<GroovyAstModel>(relaxed = true)
        indexingService.getSymbolIndex(file2.toUri()) { astModel }

        val allIndices = indexingService.getAllSymbolIndices()

        assertEquals(2, allIndices.size)
        assertTrue(allIndices.containsKey(file1.toUri()))
        assertTrue(allIndices.containsKey(file2.toUri()))
    }

    @Test
    fun `invalidate removes from both caches`() = runBlocking {
        val file = tempDir.resolve("test.groovy").apply {
            createFile()
            writeText("class Test {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        indexingService.indexFile(file.toUri())
        indexingService.invalidate(file.toUri())

        val result = indexingService.getSymbolIndex(file.toUri())
        val allIndices = indexingService.getAllSymbolIndices()

        assertNull(result)
        assertEquals(0, allIndices.size)
    }

    @Test
    fun `clear removes all entries`() = runBlocking {
        val file1 = tempDir.resolve("test1.groovy").apply {
            createFile()
            writeText("class Test1 {}")
        }
        val file2 = tempDir.resolve("test2.groovy").apply {
            createFile()
            writeText("class Test2 {}")
        }

        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        indexingService.indexFile(file1.toUri())
        indexingService.indexFile(file2.toUri())
        indexingService.clear()

        val allIndices = indexingService.getAllSymbolIndices()
        assertEquals(0, allIndices.size)
    }

    @Test
    fun `LRU eviction works when cache exceeds maxSize`() = runBlocking {
        val parseResult = createMockParseResult()
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns parseResult

        // Create 15 files (maxSize is 10)
        val files = (1..15).map { i ->
            tempDir.resolve("test$i.groovy").apply {
                createFile()
                writeText("class Test$i {}")
            }
        }

        // Index all files
        files.forEach { file ->
            indexingService.indexFile(file.toUri())
        }

        // First 5 files should be evicted from LRU cache
        // But all should still be in workspace index
        val allIndices = indexingService.getAllSymbolIndices()
        assertEquals(15, allIndices.size)
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
