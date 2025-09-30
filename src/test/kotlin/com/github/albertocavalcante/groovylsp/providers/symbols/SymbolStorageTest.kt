package com.github.albertocavalcante.groovylsp.providers.symbols
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class SymbolStorageTest {

    private lateinit var compilationService: GroovyCompilationService

    @BeforeEach
    fun setUp() {
        compilationService = TestUtils.createCompilationService()
    }

    @Test
    fun `test buildFromVisitor creates symbol storage`() = runTest {
        // Arrange
        val content = """
            class TestClass {
                def field = "test"
                def method() {
                    return field
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content to build AST
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Get visitor to build symbols
        val visitor = compilationService.getAstVisitor(uri)
        assertNotNull(visitor, "Should have AST visitor after compilation")

        // Act - Build symbols from visitor using extension function
        val symbolStorage = SymbolStorage().buildFromVisitor(visitor!!)

        // Assert - Should have symbols in the new storage
        assertTrue(symbolStorage.symbols.isNotEmpty(), "Should have symbols after building from visitor")

        // Should have symbols for this URI
        val uriSymbols = symbolStorage.symbols[uri]
        assertNotNull(uriSymbols, "Should have symbols for the compiled URI")
        assertTrue(uriSymbols!!.isNotEmpty(), "Should have at least some symbols")
    }

    @Test
    fun `test symbol lookup by name`() = runTest {
        // Arrange
        val content = """
            def variable = "test"
            def testMethod() {
                return "result"
            }
            class TestClass {
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile and build symbol storage
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val visitor = compilationService.getAstVisitor(uri)
        assertNotNull(visitor, "Should have AST visitor")

        val symbolStorage = SymbolStorage().buildFromVisitor(visitor!!)

        // Act - Search for symbols by name using the immutable API
        val allSymbols = symbolStorage.symbols[uri] ?: persistentListOf()
        val variableSymbols = allSymbols.filter { it.name == "variable" }
        val methodSymbols = allSymbols.filter { it.name == "testMethod" }
        val classSymbols = allSymbols.filter { it.name == "TestClass" }

        // Assert - Should find the defined symbols
        assertTrue(
            variableSymbols.isNotEmpty() || methodSymbols.isNotEmpty() || classSymbols.isNotEmpty(),
            "Should find at least one of the defined symbols",
        )
    }

    @Test
    fun `test search non-existent symbol`() = runTest {
        // Arrange
        val content = """
            def variable = "test"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile and build symbol storage
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val visitor = compilationService.getAstVisitor(uri)
        assertNotNull(visitor, "Should have AST visitor")

        val symbolStorage = SymbolStorage().buildFromVisitor(visitor!!)

        // Act - Search for non-existent symbol
        val allSymbols = symbolStorage.symbols[uri] ?: persistentListOf()
        val nonExistentSymbols = allSymbols.filter { it.name == "nonExistentSymbol" }

        // Assert - Should not find non-existent symbol
        assertTrue(nonExistentSymbols.isEmpty(), "Should not find non-existent symbol")
    }

    @Test
    fun `test immutable nature of SymbolStorage`() = runTest {
        // Arrange
        val content = """
            def variable = "test"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile and build symbol storage
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val visitor = compilationService.getAstVisitor(uri)
        assertNotNull(visitor, "Should have AST visitor")

        val originalStorage = SymbolStorage()
        val newStorage = originalStorage.buildFromVisitor(visitor!!)

        // Assert - Original storage should be unchanged (immutability)
        assertTrue(originalStorage.symbols.isEmpty(), "Original storage should remain empty")
        assertTrue(newStorage.symbols.isNotEmpty(), "New storage should contain symbols")

        // They should be different instances
        assertNotSame(originalStorage, newStorage, "buildFromVisitor should return new instance")
    }

    @Test
    fun `test empty storage behavior`() = runTest {
        // Arrange
        val emptyStorage = SymbolStorage()

        // Assert - Empty storage characteristics
        assertTrue(emptyStorage.symbols.isEmpty(), "Empty storage should have no symbols")
        assertTrue(emptyStorage.symbolsByName.isEmpty(), "Empty storage should have no symbols by name")
        assertTrue(emptyStorage.symbolsByCategory.isEmpty(), "Empty storage should have no symbols by category")

        // Act - Querying empty storage should not crash
        val nonExistentUri = URI.create("file:///nonexistent.groovy")
        val symbols = emptyStorage.symbols[nonExistentUri]

        // Assert - Should handle missing URIs gracefully
        assertNull(symbols, "Should return null for non-existent URI in empty storage")
    }
}
