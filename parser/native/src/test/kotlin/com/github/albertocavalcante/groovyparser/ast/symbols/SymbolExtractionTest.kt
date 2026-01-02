package com.github.albertocavalcante.groovyparser.ast.symbols

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class SymbolExtractionTest {

    private lateinit var parser: GroovyParserFacade

    @BeforeEach
    fun setUp() {
        parser = GroovyParserFacade()
    }

    @Test
    fun `test buildFromVisitor creates symbol storage`() {
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

        // Act
        val result = parser.parse(ParseRequest(uri = uri, content = content))

        // Assert compilation
        val ast = result.ast
        assertNotNull(ast, "AST should not be null")

        // Get visitor
        val visitor = result.astModel
        assertNotNull(visitor, "Should have AST model")

        // Build symbols
        val symbolIndex = SymbolIndex().buildFromVisitor(visitor)

        // Assert - Should have symbols in the new storage
        assertTrue(symbolIndex.symbols.isNotEmpty(), "Should have symbols after building from visitor")

        // Should have symbols for this URI
        val uriSymbols = symbolIndex.symbols[uri]
        assertNotNull(uriSymbols, "Should have symbols for the compiled URI")
        assertTrue(uriSymbols!!.isNotEmpty(), "Should have at least some symbols")
    }

    @Test
    fun `test symbol lookup by name`() {
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

        // Act
        val result = parser.parse(ParseRequest(uri = uri, content = content))
        val visitor = result.astModel
        assertNotNull(visitor, "Should have AST model")

        val symbolIndex = SymbolIndex().buildFromVisitor(visitor)

        // Act - Search for symbols by name using the immutable API
        val allSymbols = symbolIndex.symbols[uri] ?: persistentListOf()
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
    fun `test search non-existent symbol`() {
        // Arrange
        val content = """
            def variable = "test"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Act
        val result = parser.parse(ParseRequest(uri = uri, content = content))
        val visitor = result.astModel
        assertNotNull(visitor, "Should have AST model")

        val symbolIndex = SymbolIndex().buildFromVisitor(visitor)

        // Act - Search for non-existent symbol
        val allSymbols = symbolIndex.symbols[uri] ?: persistentListOf()
        val nonExistentSymbols = allSymbols.filter { it.name == "nonExistentSymbol" }

        // Assert - Should not find non-existent symbol
        assertTrue(nonExistentSymbols.isEmpty(), "Should not find non-existent symbol")
    }

    @Test
    fun `test immutable nature of SymbolIndex`() {
        // Arrange
        val content = """
            def variable = "test"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Act
        val result = parser.parse(ParseRequest(uri = uri, content = content))
        val visitor = result.astModel
        assertNotNull(visitor, "Should have AST model")

        val originalIndex = SymbolIndex()
        val newIndex = originalIndex.buildFromVisitor(visitor)

        // Assert - Original storage should be unchanged (immutability)
        assertTrue(originalIndex.symbols.isEmpty(), "Original storage should remain empty")
        assertTrue(newIndex.symbols.isNotEmpty(), "New storage should contain symbols")

        // They should be different instances
        assertNotSame(originalIndex, newIndex, "buildFromVisitor should return new instance")
    }

    @Test
    fun `test empty storage behavior`() {
        // Arrange
        val emptyIndex = SymbolIndex()

        // Assert - Empty storage characteristics
        assertTrue(emptyIndex.symbols.isEmpty(), "Empty storage should have no symbols")
        assertTrue(emptyIndex.symbolsByName.isEmpty(), "Empty storage should have no symbols by name")
        assertTrue(emptyIndex.symbolsByCategory.isEmpty(), "Empty storage should have no symbols by category")

        // Act - Querying empty storage should not crash
        val nonExistentUri = URI.create("file:///nonexistent.groovy")
        val symbols = emptyIndex.symbols[nonExistentUri]

        // Assert - Should handle missing URIs gracefully
        assertNull(symbols, "Should return null for non-existent URI in empty storage")
    }

    @Test
    fun `test class interfaceNames contains FQN for imported interfaces`() {
        // Arrange - Class implements interface from different package via import
        val content = """
            package impls
            
            import interfaces.Repository
            
            class UserRepository implements Repository {
                def save(Object entity) { return "saved" }
            }
        """.trimIndent()

        val uri = URI.create("file:///src/impls/UserRepository.groovy")

        // Act
        val result = parser.parse(ParseRequest(uri = uri, content = content))
        val visitor = result.astModel
        assertNotNull(visitor, "Should have AST model")

        val symbolIndex = SymbolIndex().buildFromVisitor(visitor)

        // Find the UserRepository class symbol
        val allSymbols = symbolIndex.symbols[uri] ?: persistentListOf()
        val classSymbols = allSymbols.filterIsInstance<Symbol.Class>()
        val userRepoSymbol = classSymbols.find { it.name == "UserRepository" }

        // Assert
        assertNotNull(userRepoSymbol, "Should find UserRepository class symbol")

        // Key assertion: interfaceNames should contain FQN, not simple name
        assertTrue(
            userRepoSymbol!!.interfaceNames.contains("interfaces.Repository"),
            "interfaceNames should contain FQN 'interfaces.Repository', but was: ${userRepoSymbol.interfaceNames}",
        )
    }
}
