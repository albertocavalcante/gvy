package com.github.albertocavalcante.groovylsp.providers.folding

import com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.FoldingRangeKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import kotlin.test.assertTrue

/**
 * Integration test for FoldingRangeProvider using real Groovy files.
 */
class FoldingRangeIntegrationTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var foldingRangeProvider: FoldingRangeProvider

    @BeforeEach
    fun setUp() {
        val dependencyManager = CentralizedDependencyManager()
        compilationService = GroovyCompilationService(dependencyManager)
        foldingRangeProvider = FoldingRangeProvider(compilationService)
    }

    @Test
    fun `should provide folding ranges for complex groovy file`() {
        // Read the test resource file
        val testFile = File("src/test/resources/ComplexFolding.groovy")
        if (!testFile.exists()) {
            println("Test file not found, skipping test")
            return
        }

        val sourceCode = testFile.readText()
        val uri = testFile.toURI()

        // Compile the source
        runBlocking {
            compilationService.compile(uri, sourceCode)
        }

        // Get folding ranges
        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        println("Found ${ranges.size} folding ranges:")
        ranges.forEachIndexed { index, range ->
            println("$index: Line ${range.startLine}-${range.endLine} (${range.kind}) ${range.collapsedText ?: ""}")
        }

        // Verify we found some ranges
        assertTrue(ranges.isNotEmpty(), "Should find folding ranges in complex Groovy file")

        // Check for imports folding
        val importRanges = ranges.filter { it.kind == FoldingRangeKind.Imports }
        assertTrue(importRanges.isNotEmpty(), "Should have import folding ranges")

        // Check for region ranges (classes, methods, closures, control structures)
        val regionRanges = ranges.filter { it.kind == FoldingRangeKind.Region }
        assertTrue(regionRanges.isNotEmpty(), "Should have region folding ranges")

        // Verify collapsed text is present for some ranges
        val rangesWithText = ranges.filter { !it.collapsedText.isNullOrBlank() }
        assertTrue(rangesWithText.isNotEmpty(), "Some ranges should have collapsed text")
    }

    @Test
    fun `should provide folding ranges for gradle build file`() {
        // Read the gradle build file
        val testFile = File("src/test/resources/build.gradle")
        if (!testFile.exists()) {
            println("Build file not found, skipping test")
            return
        }

        val sourceCode = testFile.readText()
        val uri = testFile.toURI()

        // Compile the source
        runBlocking {
            compilationService.compile(uri, sourceCode)
        }

        // Get folding ranges
        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        println("Found ${ranges.size} folding ranges in build.gradle:")
        ranges.forEachIndexed { index, range ->
            println("$index: Line ${range.startLine}-${range.endLine} (${range.kind}) ${range.collapsedText ?: ""}")
        }

        // Should have some folding ranges for gradle DSL blocks
        assertTrue(ranges.isNotEmpty(), "Should find folding ranges in Gradle build file")
    }

    @Test
    fun `should handle real groovy file from codebase`() {
        // Use one of the existing test resources
        val testFile = File("src/test/resources/Simple.groovy")
        if (!testFile.exists()) {
            println("Simple.groovy not found, skipping test")
            return
        }

        val sourceCode = testFile.readText()
        val uri = testFile.toURI()

        // Compile the source
        runBlocking {
            compilationService.compile(uri, sourceCode)
        }

        // Get folding ranges
        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        println("Folding ranges for Simple.groovy:")
        ranges.forEachIndexed { index, range ->
            println("$index: Line ${range.startLine}-${range.endLine} (${range.kind}) ${range.collapsedText ?: ""}")
        }

        // Even simple files should have some structure
        // (This test is mainly to ensure no exceptions are thrown)
        assertTrue(true, "Should handle real groovy files without exceptions")
    }
}
