package com.github.albertocavalcante.groovylsp.providers.symbols
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for WorkspaceSymbolProvider functionality.
 * Phase 1: Critical basic functionality tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSymbolProviderTest {

    private val logger = LoggerFactory.getLogger(WorkspaceSymbolProviderTest::class.java)

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var workspaceCompilationService: WorkspaceCompilationService
    private lateinit var provider: WorkspaceSymbolProvider
    private lateinit var testScope: TestScope

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        testScope = TestScope()
        compilationService = TestUtils.createCompilationService()
        val dependencyManager = com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager()
        workspaceCompilationService = WorkspaceCompilationService(testScope, dependencyManager)
        provider = WorkspaceSymbolProvider(
            compilationService,
            workspaceCompilationService,
            testScope,
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        workspaceCompilationService.clearWorkspace()
    }

    // PHASE 1: Core Functionality (Most Critical)

    @Test
    fun `searchSymbols returns empty list when no files indexed`() {
        logger.debug("Testing empty workspace search")

        // No files compiled yet
        val result = provider.searchSymbols("TestClass")

        assertTrue(result.isEmpty(), "Should return empty list when nothing indexed")
    }

    @Test
    fun `searchSymbols finds simple class by exact name`() = runTest {
        logger.debug("Testing simple class search")

        // Create and compile a simple class
        val code = """
            class TestClass {
                def method() { }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, code)
        assertTrue(compilationResult.isSuccess, "Compilation should succeed")

        provider.updateFileSymbols(uri)
        advanceUntilIdle() // Wait for async symbol extraction to complete

        // Debug: Check if AST is available
        val ast = compilationService.getAst(uri)
        logger.debug("AST available: ${ast != null}, AST type: ${ast?.javaClass?.simpleName}")
        logger.debug("Total indexed symbols: ${provider.getIndexedSymbolCount()}")

        // Search for the class
        val result = provider.searchSymbols("TestClass")

        assertTrue(result.isNotEmpty(), "Should find TestClass")
        val symbol = result.first()
        assertTrue(symbol.isLeft, "Should return SymbolInformation")
        assertEquals("TestClass", symbol.left.name)
        assertEquals(SymbolKind.Class, symbol.left.kind)
        assertEquals(uri.toString(), symbol.left.location.uri)
    }

    @Test
    fun `searchSymbols finds method within class`() = runTest {
        logger.debug("Testing method search with container")

        val code = """
            class MyClass {
                def findUser() {
                    return "user"
                }

                void processData() {
                    println "processing"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, code)
        assertTrue(compilationResult.isSuccess, "Compilation should succeed")

        provider.updateFileSymbols(uri)
        advanceUntilIdle() // Wait for async symbol extraction to complete

        // Search for method
        val result = provider.searchSymbols("findUser")

        assertTrue(result.isNotEmpty(), "Should find method")
        val symbol = result.first()
        assertTrue(symbol.isLeft, "Should return SymbolInformation")
        assertEquals("findUser", symbol.left.name)
        assertEquals(SymbolKind.Method, symbol.left.kind)
        assertEquals("MyClass", symbol.left.containerName, "Should have correct container")
        assertEquals(uri.toString(), symbol.left.location.uri)
    }

    @Test
    fun `searchSymbols finds symbols across multiple files`() = runTest {
        logger.debug("Testing multi-file symbol search")

        // File 1: Class with method
        val code1 = """
            class FirstClass {
                def firstMethod() { }
            }
        """.trimIndent()

        // File 2: Another class with method
        val code2 = """
            class SecondClass {
                def secondMethod() { }
            }
        """.trimIndent()

        // File 3: Interface
        val code3 = """
            interface ThirdInterface {
                void thirdMethod()
            }
        """.trimIndent()

        val uri1 = URI.create("file:///first.groovy")
        val uri2 = URI.create("file:///second.groovy")
        val uri3 = URI.create("file:///third.groovy")

        // Compile all files
        val result1 = compilationService.compile(uri1, code1)
        val result2 = compilationService.compile(uri2, code2)
        val result3 = compilationService.compile(uri3, code3)

        assertTrue(result1.isSuccess, "First file compilation should succeed")
        assertTrue(result2.isSuccess, "Second file compilation should succeed")
        assertTrue(result3.isSuccess, "Third file compilation should succeed")

        // Update symbols for all files
        provider.updateFileSymbols(uri1)
        provider.updateFileSymbols(uri2)
        provider.updateFileSymbols(uri3)
        advanceUntilIdle() // Wait for all async symbol extractions to complete

        // Search for each class
        val firstClassResults = provider.searchSymbols("FirstClass")
        val secondClassResults = provider.searchSymbols("SecondClass")
        val thirdInterfaceResults = provider.searchSymbols("ThirdInterface")

        assertTrue(firstClassResults.isNotEmpty(), "Should find FirstClass")
        assertTrue(secondClassResults.isNotEmpty(), "Should find SecondClass")
        assertTrue(thirdInterfaceResults.isNotEmpty(), "Should find ThirdInterface")

        // Verify symbol types
        assertEquals(SymbolKind.Class, firstClassResults.first().left.kind)
        assertEquals(SymbolKind.Class, secondClassResults.first().left.kind)
        assertEquals(SymbolKind.Interface, thirdInterfaceResults.first().left.kind)

        // Verify URIs
        assertEquals(uri1.toString(), firstClassResults.first().left.location.uri)
        assertEquals(uri2.toString(), secondClassResults.first().left.location.uri)
        assertEquals(uri3.toString(), thirdInterfaceResults.first().left.location.uri)
    }

    @Test
    fun `searchSymbols returns symbols with correct location URIs`() = runTest {
        logger.debug("Testing symbol location accuracy")

        val code = """
            package com.example

            class LocationTest {
                private String field = "value"

                public void testMethod() {
                    // method body
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///location.groovy")
        val compilationResult = compilationService.compile(uri, code)
        assertTrue(compilationResult.isSuccess, "Compilation should succeed")

        provider.updateFileSymbols(uri)
        advanceUntilIdle() // Wait for async symbol extraction to complete

        // Search for all symbols
        val classResults = provider.searchSymbols("LocationTest")
        val methodResults = provider.searchSymbols("testMethod")
        val fieldResults = provider.searchSymbols("field")

        // Verify all symbols found
        assertTrue(classResults.isNotEmpty(), "Should find class")
        assertTrue(methodResults.isNotEmpty(), "Should find method")
        assertTrue(fieldResults.isNotEmpty(), "Should find field")

        // Verify all have correct URI
        assertEquals(uri.toString(), classResults.first().left.location.uri)
        assertEquals(uri.toString(), methodResults.first().left.location.uri)
        assertEquals(uri.toString(), fieldResults.first().left.location.uri)

        // Verify symbol kinds
        assertEquals(SymbolKind.Class, classResults.first().left.kind)
        assertEquals(SymbolKind.Method, methodResults.first().left.kind)
        assertEquals(SymbolKind.Field, fieldResults.first().left.kind)

        // Verify method has correct container
        assertEquals("LocationTest", methodResults.first().left.containerName)
        assertEquals("LocationTest", fieldResults.first().left.containerName)
    }

    @Test
    fun `searchSymbols handles empty query gracefully`() = runTest {
        logger.debug("Testing empty query handling")

        val code = """
            class TestClass {
                def method() { }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, code)
        provider.updateFileSymbols(uri)
        advanceUntilIdle() // Wait for async symbol extraction to complete

        // Empty query should return limited results
        val result = provider.searchSymbols("")

        // Should not crash and should return some results (but limited)
        assertTrue(result.size <= 100, "Empty query should limit results to avoid overwhelming client")
    }

    @Test
    fun `searchSymbols handles non-existent symbols gracefully`() {
        logger.debug("Testing search for non-existent symbols")

        // Search for something that definitely doesn't exist
        val result = provider.searchSymbols("NonExistentSymbolThatShouldNeverBeFound")

        assertTrue(result.isEmpty(), "Should return empty list for non-existent symbols")
    }
}
