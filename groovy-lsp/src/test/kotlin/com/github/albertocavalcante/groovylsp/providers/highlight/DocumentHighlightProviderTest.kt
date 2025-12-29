package com.github.albertocavalcante.groovylsp.providers.highlight

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class DocumentHighlightProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var highlightProvider: DocumentHighlightProvider

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        highlightProvider = DocumentHighlightProvider(compilationService)
    }

    @Test
    fun `test highlight variable references in same scope`() = runTest {
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

        // Act - Find highlights for 'localVar' at its declaration (line 0, column 6)
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 6), // pointing at 'ocalVar' in declaration
        )

        // Assert
        assertEquals(3, highlights.size, "Should find declaration + 2 usages")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }
        assertEquals(1, writes.size, "Should find 1 write (declaration)")
        assertEquals(2, reads.size, "Should find 2 reads (usages)")
    }

    @Test
    fun `test highlight with read and write classification`() = runTest {
        // Arrange
        val content = """
            def counter = 0
            counter = counter + 1
            println counter
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find highlights for 'counter'
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(4, highlights.size, "Should find 4 highlights for 'counter'")

        // Count writes (declaration + assignment) and reads
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }

        assertEquals(2, writes.size, "Should find 2 write highlights (declaration and assignment)")
        assertEquals(2, reads.size, "Should find 2 read highlights (RHS of assignment and println)")
    }

    @Test
    fun `test highlight with prefix and postfix operators`() = runTest {
        // Arrange
        val content = """
            def i = 0
            i++
            ++i
            println i
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find highlights for 'i'
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(4, highlights.size, "Should find 4 highlights for 'i'")

        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }

        // Expected writes:
        // 1. def i = 0
        // 2. i++
        // 3. ++i
        assertEquals(3, writes.size, "Should find 3 write highlights (declaration, postfix, prefix)")

        // Expected reads:
        // 1. println i
        assertEquals(1, reads.size, "Should find 1 read highlight (println)")
    }

    @Test
    fun `test highlight with compound assignments`() = runTest {
        // Arrange
        val content = """
            def x = 1
            x += 2
            x **= 3
            println x
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(4, highlights.size, "Should find 4 highlights")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }

        // Writes: declaration, +=, **=
        assertEquals(3, writes.size, "Should find 3 writes")
        // Reads: println
        assertEquals(1, reads.size, "Should find 1 read")
    }

    @Test
    fun `test no highlights for position with no symbol`() = runTest {
        // Arrange
        val content = """
            def localVar = "test"
            println localVar
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Try to find highlights inside string literal
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 17), // inside "test" string
        )

        // Assert
        assertTrue(highlights.isEmpty(), "Should not find highlights inside string literal")
    }

    @Test
    fun `test highlights without compilation`() {
        // Act - Try to find highlights for a file that hasn't been compiled
        val highlights = highlightProvider.provideHighlights(
            "file:///unknown.groovy",
            Position(0, 0),
        )

        // Assert
        assertTrue(highlights.isEmpty(), "Should not find highlights without compilation")
    }

    @Test
    fun `test highlight method references`() = runTest {
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

        // Act - Find highlights for 'testMethod'
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(3, highlights.size, "Should find declaration + 2 calls")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }
        // Method declarations are considered reads for now as they are not assignments,
        // but we might want to classify them as Write in the future.
        // Currently DocumentHighlightProvider.classifyHighlightKind returns Read for MethodNode.
        assertEquals(0, writes.size, "Method declaration and calls are currently classified as Read")
        assertEquals(3, reads.size, "Should find 3 reads (1 declaration + 2 calls)")
    }

    @Test
    fun `test highlight parameter references`() = runTest {
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

        // Act - Find highlights for 'param' parameter
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 15),
        )

        // Assert
        assertEquals(3, highlights.size, "Should find 1 declaration + 2 usages")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }
        assertEquals(1, writes.size, "Should find 1 write (parameter declaration)")
        assertEquals(2, reads.size, "Should find 2 reads (usages)")
    }

    @Test
    fun `test highlight parameter references with scoping`() = runTest {
        // Arrange
        val content = """
            def method1(param) {
                println param
            }
            def method2(param) {
                println param
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find highlights for 'param' in method1
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(1, 12), // pointing at 'param' in println of method1
        )

        // Assert
        // Should find declaration (line 0) and usage (line 1) in method1
        // Should NOT find anything in method2 (lines 3, 4)
        assertEquals(2, highlights.size, "Should only find 2 highlights for method1's param")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }
        assertEquals(1, writes.size, "Should find 1 write (parameter declaration)")
        assertEquals(1, reads.size, "Should find 1 read (usage)")
        assertTrue(highlights.all { it.range.start.line <= 1 }, "All highlights should be in method1")
    }

    @Test
    fun `test highlight with bitwise compound assignments`() = runTest {
        // Arrange
        val content = """
            def flags = 0xFF
            flags &= 0x0F
            flags |= 0x10
            flags ^= 0x01
            println flags
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(5, highlights.size, "Should find 5 highlights")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }

        // Writes: declaration, &=, |=, ^=
        assertEquals(4, writes.size, "Should find 4 writes (declaration + 3 bitwise assignments)")
        // Reads: println
        assertEquals(1, reads.size, "Should find 1 read")
    }

    @Test
    fun `test highlight with shift compound assignments`() = runTest {
        // Arrange
        val content = """
            def bits = 1
            bits <<= 2
            bits >>= 1
            println bits
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(4, highlights.size, "Should find 4 highlights")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }

        // Writes: declaration, <<=, >>=
        assertEquals(3, writes.size, "Should find 3 writes (declaration + 2 shift assignments)")
        // Reads: println
        assertEquals(1, reads.size, "Should find 1 read")
    }

    @Test
    fun `test highlight field references`() = runTest {
        // Arrange
        val content = """
            class MyClass {
                def myField = "initial"
                
                def useField() {
                    println myField
                    myField = "updated"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find highlights for 'myField' at declaration
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(1, 8),
        )

        // Assert
        assertFalse(highlights.isEmpty(), "Should find highlights for field")
    }

    @Test
    fun `test highlight class references`() = runTest {
        // Arrange
        val content = """
            class Person {
                String name
            }
            def p = new Person()
            Person other = null
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find highlights for 'Person' class
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 6),
        )

        // Assert - Should find class declaration and usages
        assertFalse(highlights.isEmpty(), "Should find highlights for class")
    }

    @Test
    fun `test highlight at position zero`() = runTest {
        // Arrange - variable at very beginning of file
        val content = """def x = 1
println x"""

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find highlights at position (0, 0)
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 0),
        )

        // Assert - Should handle position 0,0 gracefully
        // The exact behavior depends on what's at position 0,0
        // This test ensures no exceptions are thrown
        assertTrue(true, "Should not throw exception at position 0,0")
    }

    @Test
    fun `test no cross-file highlights`() = runTest {
        // Arrange - Compile two files with same variable name
        val uri1 = URI.create("file:///file1.groovy")
        val uri2 = URI.create("file:///file2.groovy")

        val content1 = """
            def sharedName = "file1"
            println sharedName
        """.trimIndent()

        val content2 = """
            def sharedName = "file2"
            println sharedName
        """.trimIndent()

        // Compile both files
        val result1 = compilationService.compile(uri1, content1)
        val result2 = compilationService.compile(uri2, content2)
        assertTrue(result1.isSuccess, "Compilation of file1 should succeed")
        assertTrue(result2.isSuccess, "Compilation of file2 should succeed")

        // Act - Find highlights for 'sharedName' in file1
        val highlights = highlightProvider.provideHighlights(
            uri1.toString(),
            Position(0, 4),
        )

        // Assert - Should only find 2 highlights (both in file1), not 4 (from both files)
        assertEquals(2, highlights.size, "Should only find highlights in current file, not cross-file")
    }

    @Test
    fun `test highlight for unresolvable symbol returns empty`() = runTest {
        // Arrange - Reference to undefined variable
        val content = """
            println undefinedVariable
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Act - Try to find highlights for undefined variable
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 8),
        )

        // Assert - Should return empty list for unresolvable symbols
        assertTrue(highlights.isEmpty(), "Should return empty for unresolvable symbol")
    }

    @Test
    fun `test highlight with multiple assignment operators`() = runTest {
        // Arrange - Test various assignment operators together
        val content = """
            def v = 10
            v -= 2
            v *= 3
            v /= 2
            v %= 3
            println v
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act
        val highlights = highlightProvider.provideHighlights(
            uri.toString(),
            Position(0, 4),
        )

        // Assert
        assertEquals(6, highlights.size, "Should find 6 highlights")
        val writes = highlights.filter { it.kind == DocumentHighlightKind.Write }
        val reads = highlights.filter { it.kind == DocumentHighlightKind.Read }

        // Writes: declaration, -=, *=, /=, %=
        assertEquals(5, writes.size, "Should find 5 writes")
        // Reads: println
        assertEquals(1, reads.size, "Should find 1 read")
    }
}
