package com.github.albertocavalcante.groovylsp.compilation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceCompilationTest {

    private lateinit var workspaceService: WorkspaceCompilationService
    private lateinit var coroutineScope: CoroutineScope

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        coroutineScope = CoroutineScope(Dispatchers.Default)
        val dependencyManager = com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager()
        workspaceService = WorkspaceCompilationService(coroutineScope, dependencyManager)
    }

    @AfterEach
    fun tearDown() = runBlocking {
        workspaceService.clearWorkspace()
    }

    @Test
    fun `should resolve class from same package`() = runBlocking {
        // Create temporary files for cross-file resolution test
        val file1 = tempDir.resolve("ParallelDeclaration.groovy")
        val file2 = tempDir.resolve("StageDeclaration.groovy")

        // File 1: ParallelDeclaration.groovy
        val file1Content = """
            package com.example.pipeline

            class ParallelDeclaration {
                String name
                List<String> branches = []

                void addBranch(String branch) {
                    branches.add(branch)
                }
            }
        """.trimIndent()

        // File 2: StageDeclaration.groovy (references ParallelDeclaration)
        val file2Content = """
            package com.example.pipeline

            class StageDeclaration {
                String name
                ParallelDeclaration parallel  // This should resolve!

                void setParallel(ParallelDeclaration p) {
                    this.parallel = p
                }
            }
        """.trimIndent()

        Files.write(file1, file1Content.toByteArray())
        Files.write(file2, file2Content.toByteArray())

        val file1Uri = file1.toUri()
        val file2Uri = file2.toUri()

        // Initialize workspace with temp directory
        val result = workspaceService.initializeWorkspace(tempDir)

        // Verify compilation succeeds
        assertTrue(result.isSuccess, "Workspace compilation should succeed")

        // Verify both files are compiled
        assertTrue(result.modulesByUri.containsKey(file1Uri), "ParallelDeclaration should be compiled")
        assertTrue(result.modulesByUri.containsKey(file2Uri), "StageDeclaration should be compiled")

        // Verify symbol table contains both classes
        val symbolTable = workspaceService.getWorkspaceSymbolTable()
        assertNotNull(symbolTable, "Symbol table should be available")

        // Get workspace statistics
        val stats = workspaceService.getWorkspaceStatistics()
        assertEquals(2, stats["totalFiles"], "Should have 2 files")
        assertEquals(2, stats["compiledModules"], "Should have 2 compiled modules")

        println("Workspace compilation test completed successfully!")
        println("Files compiled: ${result.modulesByUri.keys.map { it.path.substringAfterLast("/") }}")
        println("Diagnostics: ${result.diagnostics.values.sumOf { it.size }} total")
    }

    @Test
    fun `should handle file updates incrementally`() = runBlocking {
        // Create file in temp directory BEFORE workspace initialization
        val file1 = tempDir.resolve("Example.groovy")
        val file1Uri = file1.toUri()
        val initialContent = """
            package com.example
            class Example {
                String name = "initial"
            }
        """.trimIndent()

        // Create the file on disk first
        Files.write(file1, initialContent.toByteArray())

        // Initialize workspace - should find the existing file
        workspaceService.initializeWorkspace(tempDir)

        // Update file content (file should now be in a context)
        val result1 = workspaceService.updateFile(file1Uri, initialContent)
        assertTrue(result1.isSuccess, "Initial compilation should succeed")

        // Update file content
        val updatedContent = """
            package com.example
            class Example {
                String name = "updated"
                int version = 2
            }
        """.trimIndent()

        // Write updated content to disk
        Files.write(file1, updatedContent.toByteArray())

        // Update file
        val result2 = workspaceService.updateFile(file1Uri, updatedContent)
        assertTrue(result2.isSuccess, "Updated compilation should succeed")

        // Verify updated module is available
        val updatedModule = result2.modulesByUri[file1Uri]
        assertNotNull(updatedModule, "Updated module should be available")
    }

    @Test
    fun `should handle compilation errors gracefully`() = runBlocking {
        val fileUri = URI.create("file:///test/Invalid.groovy")
        val invalidContent = """
            package com.example
            class Invalid {
                // Invalid syntax
                String name =
            }
        """.trimIndent()

        // Add file with syntax error
        val result = workspaceService.updateFile(fileUri, invalidContent)

        // Should not crash, even with syntax errors
        assertNotNull(result, "Result should not be null even with syntax errors")

        // May or may not succeed depending on error handling, but should not crash
        println("Compilation with syntax error handled: success=${result.isSuccess}")
    }
}
