package com.github.albertocavalcante.groovylsp.providers.formatting

import com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormattingProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var formattingProvider: FormattingProvider

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        val dependencyManager = CentralizedDependencyManager()
        compilationService = GroovyCompilationService(dependencyManager)
        formattingProvider = FormattingProvider(compilationService)
    }

    @Test
    fun `test basic indentation formatting`() {
        val unformattedContent = """
class TestClass {
def method() {
println "hello"
if (true) {
println "world"
}
}
}
        """.trimIndent()

        val expectedContent = """
class TestClass {
    def method() {
        println "hello"
        if (true) {
            println "world"
        }
    }
}
        """.trimIndent()

        val testFile = tempDir.resolve("TestClass.groovy")
        Files.writeString(testFile, unformattedContent)

        val options = FormattingOptions().apply {
            tabSize = 4
            isInsertSpaces = true
        }

        val edits = formattingProvider.formatDocument(testFile.toUri().toString(), options)

        assertTrue(edits.isNotEmpty(), "Expected formatting edits")
        assertEquals(1, edits.size, "Expected single edit replacing entire document")

        val edit = edits.first()
        val formattedContent = edit.newText.trim()
        assertEquals(expectedContent, formattedContent, "Formatted content should match expected")
    }

    @Test
    fun `test formatting with tabs`() {
        val unformattedContent = """
class TestClass {
def method() {
println "hello"
}
}
        """.trimIndent()

        val testFile = tempDir.resolve("TestClass.groovy")
        Files.writeString(testFile, unformattedContent)

        val options = FormattingOptions().apply {
            tabSize = 4
            isInsertSpaces = false // Use tabs
        }

        val edits = formattingProvider.formatDocument(testFile.toUri().toString(), options)

        assertTrue(edits.isNotEmpty(), "Expected formatting edits")

        val formattedContent = edits.first().newText
        assertTrue(formattedContent.contains("\t"), "Should use tabs for indentation")
    }

    @Test
    fun `test range formatting`() {
        val content = """
class TestClass {
def method1() {
println "method1"
}
def method2() {
println "method2"
}
}
        """.trimIndent()

        val testFile = tempDir.resolve("TestClass.groovy")
        Files.writeString(testFile, content)

        val options = FormattingOptions().apply {
            tabSize = 4
            isInsertSpaces = true
        }

        // Format only the first method (lines 1-3)
        val range = Range(
            Position(1, 0), // Start of "def method1()"
            Position(3, 1)  // End of first method block
        )

        val edits = formattingProvider.formatRange(testFile.toUri().toString(), range, options)

        // Range formatting should work without errors
        // Note: Our current implementation formats the selected lines with proper indentation
        assertTrue(edits.size <= 1, "Should return at most one edit for range formatting")
    }

    @Test
    fun `test empty file formatting`() {
        val emptyContent = ""
        val testFile = tempDir.resolve("Empty.groovy")
        Files.writeString(testFile, emptyContent)

        val options = FormattingOptions().apply {
            tabSize = 4
            isInsertSpaces = true
        }

        val edits = formattingProvider.formatDocument(testFile.toUri().toString(), options)

        // Empty file should need minimal formatting (just trailing newline)
        assertTrue(edits.size <= 1, "Empty file should need at most one edit")
    }

    @Test
    fun `test already formatted file`() {
        val wellFormattedContent = """
class TestClass {
    def method() {
        println "hello"
        if (true) {
            println "world"
        }
    }
}
        """.trimIndent()

        val testFile = tempDir.resolve("WellFormatted.groovy")
        Files.writeString(testFile, wellFormattedContent)

        val options = FormattingOptions().apply {
            tabSize = 4
            isInsertSpaces = true
        }

        val edits = formattingProvider.formatDocument(testFile.toUri().toString(), options)

        // Well-formatted file should need minimal changes (maybe just trailing newline)
        assertTrue(edits.size <= 1, "Well-formatted file should need minimal changes")
    }

    @Test
    fun `test complex brace scenarios`() {
        val complexContent = """
class TestClass {
def complexMethod() {
[1, 2, 3].each { item ->
println item
}
if (true) { println "inline" }
def closure = { x, y ->
return x + y
}
}
}
        """.trimIndent()

        val testFile = tempDir.resolve("Complex.groovy")
        Files.writeString(testFile, complexContent)

        val options = FormattingOptions().apply {
            tabSize = 4
            isInsertSpaces = true
        }

        val edits = formattingProvider.formatDocument(testFile.toUri().toString(), options)

        // Complex scenarios should be handled without errors
        assertTrue(edits.isNotEmpty(), "Complex content should need formatting")

        val formattedContent = edits.first().newText
        assertTrue(formattedContent.contains("    "), "Should have proper indentation")
    }
}