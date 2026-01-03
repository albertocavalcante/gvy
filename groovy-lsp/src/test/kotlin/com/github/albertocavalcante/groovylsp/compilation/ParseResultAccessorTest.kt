package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseResultAccessorTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var accessor: ParseResultAccessor
    private lateinit var cacheService: CompilationCacheService
    private lateinit var workerSessionManager: WorkerSessionManager
    private lateinit var workspaceManager: WorkspaceManager

    @BeforeTest
    fun setup() {
        cacheService = mockk(relaxed = true)
        workerSessionManager = mockk(relaxed = true)
        workspaceManager = mockk(relaxed = true)

        accessor = ParseResultAccessor(
            cacheService = cacheService,
            workerSessionManager = workerSessionManager,
            workspaceManager = workspaceManager,
        )
    }

    @Test
    fun `getParseResult delegates to cacheService`() {
        val uri = URI.create("file:///test.groovy")
        val parseResult = createMockParseResult()

        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getParseResult(uri)

        assertEquals(parseResult, result)
    }

    @Test
    fun `getAst returns AST from parse result`() {
        val uri = URI.create("file:///test.groovy")
        val moduleNode = mockk<ModuleNode>(relaxed = true)
        val parseResult = createMockParseResult(ast = moduleNode)

        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getAst(uri)

        assertEquals(moduleNode, result)
    }

    @Test
    fun `getAst returns null when no parse result`() {
        val uri = URI.create("file:///test.groovy")

        every { cacheService.getCached(uri) } returns null

        val result = accessor.getAst(uri)

        assertNull(result)
    }

    @Test
    fun `getDiagnostics converts and returns diagnostics`() {
        val uri = URI.create("file:///test.groovy")
        val parseResult = createMockParseResult()

        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getDiagnostics(uri)

        assertNotNull(result)
        assertTrue(result.isEmpty()) // Mock has empty diagnostics
    }

    @Test
    fun `getDiagnostics returns empty list when no parse result`() {
        val uri = URI.create("file:///test.groovy")

        every { cacheService.getCached(uri) } returns null

        val result = accessor.getDiagnostics(uri)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAstModel returns AST model from parse result`() {
        val uri = URI.create("file:///test.groovy")
        val astModel = mockk<GroovyAstModel>(relaxed = true)
        val parseResult = createMockParseResult(astModel = astModel)

        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getAstModel(uri)

        assertEquals(astModel, result)
    }

    @Test
    fun `getSymbolTable returns symbol table from parse result`() {
        val uri = URI.create("file:///test.groovy")
        val parseResult = createMockParseResult()

        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getSymbolTable(uri)

        assertNotNull(result)
    }

    @Test
    fun `getTokenIndex returns token index from parse result`() {
        val uri = URI.create("file:///test.groovy")
        val parseResult = createMockParseResult()

        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getTokenIndex(uri)

        assertNotNull(result)
    }

    @Test
    fun `isSuspiciousScript detects Script fallback pattern`() {
        val uri = URI.create("file:///TestScript.groovy")

        // Create a class that extends groovy.lang.Script with matching filename
        val scriptSuperClass = mockk<ClassNode>(relaxed = true)
        every { scriptSuperClass.name } returns "groovy.lang.Script"

        val scriptClass = mockk<ClassNode>(relaxed = true)
        every { scriptClass.name } returns "TestScript"
        every { scriptClass.superClass } returns scriptSuperClass

        val moduleNode = mockk<ModuleNode>(relaxed = true)
        every { moduleNode.classes } returns listOf(scriptClass)

        val parseResult = createMockParseResult(ast = moduleNode)

        val result = accessor.isSuspiciousScript(uri, parseResult)

        assertTrue(result)
    }

    @Test
    fun `isSuspiciousScript returns false for normal classes`() {
        val uri = URI.create("file:///NormalClass.groovy")

        // Create a class that doesn't extend groovy.lang.Script
        val superClass = mockk<ClassNode>(relaxed = true)
        every { superClass.name } returns "java.lang.Object"

        val normalClass = mockk<ClassNode>(relaxed = true)
        every { normalClass.name } returns "NormalClass"
        every { normalClass.superClass } returns superClass

        val moduleNode = mockk<ModuleNode>(relaxed = true)
        every { moduleNode.classes } returns listOf(normalClass)

        val parseResult = createMockParseResult(ast = moduleNode)

        val result = accessor.isSuspiciousScript(uri, parseResult)

        assertFalse(result)
    }

    @Test
    fun `isSuspiciousScript returns false when multiple classes`() {
        val uri = URI.create("file:///Multiple.groovy")

        val class1 = mockk<ClassNode>(relaxed = true)
        val class2 = mockk<ClassNode>(relaxed = true)

        val moduleNode = mockk<ModuleNode>(relaxed = true)
        every { moduleNode.classes } returns listOf(class1, class2)

        val parseResult = createMockParseResult(ast = moduleNode)

        val result = accessor.isSuspiciousScript(uri, parseResult)

        assertFalse(result)
    }

    @Test
    fun `isSuspiciousScript returns false for interfaces`() {
        val uri = URI.create("file:///TestInterface.groovy")

        // Interface has null superClass
        val interfaceNode = mockk<ClassNode>(relaxed = true)
        every { interfaceNode.name } returns "TestInterface"
        every { interfaceNode.superClass } returns null

        val moduleNode = mockk<ModuleNode>(relaxed = true)
        every { moduleNode.classes } returns listOf(interfaceNode)

        val parseResult = createMockParseResult(ast = moduleNode)

        val result = accessor.isSuspiciousScript(uri, parseResult)

        assertFalse(result)
    }

    @Test
    fun `getValidParseResult returns cached if not suspicious`() = runBlocking {
        val uri = URI.create("file:///NormalClass.groovy")

        val superClass = mockk<ClassNode>(relaxed = true)
        every { superClass.name } returns "java.lang.Object"

        val normalClass = mockk<ClassNode>(relaxed = true)
        every { normalClass.name } returns "NormalClass"
        every { normalClass.superClass } returns superClass

        val moduleNode = mockk<ModuleNode>(relaxed = true)
        every { moduleNode.classes } returns listOf(normalClass)

        val parseResult = createMockParseResult(ast = moduleNode)
        every { cacheService.getCached(uri) } returns parseResult

        val result = accessor.getValidParseResult(uri)

        assertEquals(parseResult, result)
    }

    @Test
    fun `getValidParseResult re-compiles if suspicious Script detected`() = runBlocking {
        val file = tempDir.resolve("TestScript.groovy").apply {
            createFile()
            writeText("class TestScript {}")
        }
        val uri = file.toUri()

        // Setup suspicious Script in cache
        val scriptSuperClass = mockk<ClassNode>(relaxed = true)
        every { scriptSuperClass.name } returns "groovy.lang.Script"

        val scriptClass = mockk<ClassNode>(relaxed = true)
        every { scriptClass.name } returns "TestScript"
        every { scriptClass.superClass } returns scriptSuperClass

        val cachedModuleNode = mockk<ModuleNode>(relaxed = true)
        every { cachedModuleNode.classes } returns listOf(scriptClass)

        val cachedResult = createMockParseResult(ast = cachedModuleNode)
        every { cacheService.getCached(uri) } returns cachedResult

        // Setup fresh parse result
        val freshSuperClass = mockk<ClassNode>(relaxed = true)
        every { freshSuperClass.name } returns "java.lang.Object"

        val freshClass = mockk<ClassNode>(relaxed = true)
        every { freshClass.name } returns "TestScript"
        every { freshClass.superClass } returns freshSuperClass

        val freshModuleNode = mockk<ModuleNode>(relaxed = true)
        every { freshModuleNode.classes } returns listOf(freshClass)

        val freshResult = createMockParseResult(ast = freshModuleNode)
        coEvery { workerSessionManager.parse(any<ParseRequest>()) } returns freshResult
        every { workspaceManager.getClasspathForFile(any(), any()) } returns emptyList()

        val result = accessor.getValidParseResult(uri)

        assertNotNull(result)
        assertEquals(freshResult, result)
    }

    @Test
    fun `getValidParseResult returns null when not cached`() = runBlocking {
        val uri = URI.create("file:///test.groovy")

        every { cacheService.getCached(uri) } returns null

        val result = accessor.getValidParseResult(uri)

        assertNull(result)
    }

    // Helper methods

    private fun createMockParseResult(
        ast: ModuleNode? = mockk(relaxed = true),
        astModel: GroovyAstModel? = mockk(relaxed = true),
    ): ParseResult = ParseResult(
        ast = ast,
        astModel = astModel ?: mockk(relaxed = true),
        diagnostics = emptyList(),
        symbolTable = mockk(relaxed = true),
        tokenIndex = mockk(relaxed = true),
        compilationUnit = mockk(relaxed = true),
        sourceUnit = mockk(relaxed = true),
    )
}
