package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SelectionRangePrecisionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var definitionProvider: DefinitionProvider

    @BeforeEach
    fun setUp() = runBlocking {
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(tempDir)
        definitionProvider = DefinitionProvider(compilationService)
    }

    @Test
    fun `variable reference should have exact selection range without spaces`() = runTest {
        val file = tempDir.resolve("Test.groovy")
        val code = """
            def myVariable = 10
            println myVariable
        """.trimIndent()
        Files.writeString(file, code)

        // Compile the file
        compilationService.compile(file.toUri(), code)

        // Get definition link at 'myVariable' on line 2 (0-indexed: line 1)
        // "println myVariable" - myVariable starts at column 8
        val link = definitionProvider.provideDefinitionLinks(
            file.toUri().toString(),
            Position(1, 10), // Inside 'myVariable'
        ).firstOrNull()

        assertNotNull(link, "Definition link should be found")
        val selectionRange = link.originSelectionRange
        assertNotNull(selectionRange, "Origin selection range should be set")

        // Debug output to see what we're getting
        println("Selection range: start=${selectionRange.start.character}, end=${selectionRange.end.character}")
        println("Code line: '${code.lines()[1]}'")
        println(
            "Selected text: '${code.lines()[1].substring(
                selectionRange.start.character,
                selectionRange.end.character,
            )}'",
        )

        // Verify the range exactly covers "myVariable" (10 characters)
        assertEquals(1, selectionRange.start.line, "Start line should be 1")
        assertEquals(8, selectionRange.start.character, "Start should be at 'myVariable' start")
        assertEquals(18, selectionRange.end.character, "End should be at 'myVariable' end")
    }

    @Test
    fun `constant string should have exact selection range`() = runTest {
        val file = tempDir.resolve("Test.groovy")
        val code = """
            def text = "hello"
            println text
        """.trimIndent()
        Files.writeString(file, code)

        compilationService.compile(file.toUri(), code)

        val link = definitionProvider.provideDefinitionLinks(
            file.toUri().toString(),
            Position(1, 10),
        ).firstOrNull()

        assertNotNull(link)
        val selectionRange = link.originSelectionRange
        assertNotNull(selectionRange)

        // Debug output
        println("Selection range: start=${selectionRange.start.character}, end=${selectionRange.end.character}")
        println("Code line: '${code.lines()[1]}'")
        println(
            "Selected text: '${code.lines()[1].substring(
                selectionRange.start.character,
                selectionRange.end.character,
            )}'",
        )

        // "text" is 4 characters
        assertEquals(1, selectionRange.start.line)
        assertEquals(8, selectionRange.start.character)
        assertEquals(12, selectionRange.end.character)
    }

    @Test
    fun `variable with leading spaces should not include spaces in selection`() = runTest {
        val file = tempDir.resolve("Test.groovy")
        val code = """
            def myVar = 5
            if (true) {
                println   myVar
            }
        """.trimIndent()
        Files.writeString(file, code)

        compilationService.compile(file.toUri(), code)

        // Click on myVar in line with extra spaces
        val link = definitionProvider.provideDefinitionLinks(
            file.toUri().toString(),
            Position(2, 15), // Inside 'myVar'
        ).firstOrNull()

        assertNotNull(link)
        val selectionRange = link.originSelectionRange
        assertNotNull(selectionRange)

        println("Selection range: start=${selectionRange.start.character}, end=${selectionRange.end.character}")
        println("Code line: '${code.lines()[2]}'")

        // Extract the selected text - ensure no leading/trailing spaces
        val selectedText = code.lines()[2].substring(selectionRange.start.character, selectionRange.end.character)
        println("Selected text: '$selectedText'")

        // Should be exactly "myVar", not "   myVar" or "myVar "
        assertEquals("myVar", selectedText, "Selection should not include surrounding spaces")
    }
}
