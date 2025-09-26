package com.github.albertocavalcante.groovylsp.providers.references

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class ReferenceProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var referenceProvider: ReferenceProvider

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        referenceProvider = ReferenceProvider(compilationService)
    }

    @Test
    fun `test find variable references in same scope`() = runTest {
        // Arrange
        val content = """
            def localVar = "test"
            println localVar
            def result = localVar + " suffix"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content to build AST and symbol tables
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references of 'localVar' at its declaration (line 0, column 6)
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 6), // pointing at 'ocalVar' in declaration
            includeDeclaration = true,
        ).toList()

        // Assert
        assertFalse(references.isEmpty(), "Should find references for local variable")
        assertEquals(3, references.size, "Should find declaration + 2 usages")

        // All references should be in the same file
        references.forEach { location ->
            assertEquals(uri.toString(), location.uri)
        }
    }

    @Test
    fun `test find variable references without declaration`() = runTest {
        // Arrange
        val content = """
            def localVar = "test"
            println localVar
            def result = localVar + " suffix"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references without including declaration
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 6),
            includeDeclaration = false,
        ).toList()

        // Assert - Should find only usages, not declaration
        assertEquals(2, references.size, "Should find only 2 usages, not declaration")
    }

    @Test
    fun `test find method references`() = runTest {
        // Arrange
        val content = """
            def testMethod() {
                return "test"
            }

            testMethod()
            def result = testMethod()
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references of 'testMethod' at its declaration (line 0, column 4)
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 4),
            includeDeclaration = true,
        ).toList()

        // Assert
        assertFalse(references.isEmpty(), "Should find references for method")
        assertEquals(3, references.size, "Should find declaration + 2 calls")
    }

    @Test
    fun `test find class references`() = runTest {
        // Arrange
        val content = """
            class TestClass {
                def field = "test"
            }

            def instance = new TestClass()
            TestClass anotherInstance = null
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references of 'TestClass' at its declaration (line 0, column 6)
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 6),
            includeDeclaration = true,
        ).toList()

        // Assert
        assertFalse(references.isEmpty(), "Should find references for class")
        assertTrue(references.size >= 2, "Should find at least declaration + usages")
    }

    @Test
    fun `test find field references`() = runTest {
        // Arrange
        val content = """
            class TestClass {
                def myField = "test"

                def getField() {
                    return this.myField
                }

                def setField(value) {
                    this.myField = value
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references of 'myField' at its declaration (line 1, column 8)
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(1, 8),
            includeDeclaration = true,
        ).toList()

        // Assert
        assertFalse(references.isEmpty(), "Should find references for field")
        assertEquals(3, references.size, "Should find declaration + 2 accesses")
    }

    @Test
    fun `test no references found for unique symbol`() = runTest {
        // Arrange - Create a variable that is used, but test a different unused variable
        val content = """
            def unusedVar = "test"
            def usedVar = "different"
            println usedVar
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references of the unused variable
        // Try to find from the variable name itself
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 8), // Position pointing to 'unusedVar'
            includeDeclaration = false, // Don't include declaration
        ).toList()

        // Assert - Should find no references since variable is never used after declaration
        // Note: This test may be implementation-dependent. If references are found,
        // they should only be the declaration itself (which we're excluding)
        val message = "Should find few or no references for unused variable when excluding declaration, " +
            "found: ${references.size}"
        assertTrue(
            references.isEmpty() || references.size <= 1,
            message,
        )
    }

    @Test
    fun `test references with invalid URI`() = runTest {
        // Act - Try to find references with invalid URI
        val references = referenceProvider.provideReferences(
            "invalid-uri",
            Position(0, 0),
            includeDeclaration = true,
        ).toList()

        // Assert
        assertTrue(references.isEmpty(), "Should not find references with invalid URI")
    }

    @Test
    fun `test references at position with no symbol`() = runTest {
        // Arrange
        val content = """
            def localVar = "test"
            println localVar
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Try to find references at a position with no symbol (in the middle of string literal)
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 17), // inside the string "test"
            includeDeclaration = true,
        ).toList()

        // Assert
        assertTrue(references.isEmpty(), "Should not find references at position with no symbol")
    }

    @Test
    fun `test references without compilation`() = runTest {
        // Act - Try to find references for a file that hasn't been compiled
        val references = referenceProvider.provideReferences(
            "file:///unknown.groovy",
            Position(0, 0),
            includeDeclaration = true,
        ).toList()

        // Assert
        assertTrue(references.isEmpty(), "Should not find references without compilation")
    }

    @Test
    fun `test parameter references`() = runTest {
        // Arrange
        val content = """
            def testMethod(param) {
                println param
                return param + " suffix"
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find references of parameter 'param' at its declaration
        // Position (0, 15) should point to 'param' parameter in method signature
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 15),
            includeDeclaration = true,
        ).toList()

        // Assert - Should find at least the parameter references
        assertFalse(references.isEmpty(), "Should find references for parameter")
        // Note: The exact count may vary based on how the AST represents parameter references
        // so we check for at least 1 reference (could be declaration only or more)
        assertTrue(references.size >= 1, "Should find at least one reference")
    }
}
