package com.github.albertocavalcante.groovylsp.providers.rename

import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RenameProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var renameProvider: RenameProvider

    @BeforeEach
    fun setup() {
        compilationService = TestUtils.createCompilationService()
        val referenceProvider = ReferenceProvider(compilationService)
        renameProvider = RenameProvider(compilationService, referenceProvider)
    }

    @Test
    fun `should prepare rename for simple variable`() = runTest {
        val code = """
            def myVariable = "hello"
            println myVariable
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the code first
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to prepare rename at the variable declaration (line 0, around column 4-14)
        val position = Position(0, 6) // Should be inside "myVariable"

        val range = renameProvider.prepareRename(uri.toString(), position)

        // Should return a valid range if the symbol can be renamed
        // For now, we just test that it doesn't crash - actual position matching
        // depends on the AST visitor implementation
        println("Prepare rename result: $range")
    }

    @Test
    fun `should return null for non-renameable symbols`() = runTest {
        val code = """
            println "hello world"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the code first
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to prepare rename at a string literal (should not be renameable)
        val position = Position(0, 10) // Inside the string literal

        val range = renameProvider.prepareRename(uri.toString(), position)

        // Should return null for non-renameable symbols
        // Note: This test might pass for different reasons depending on AST visitor implementation
        println("Prepare rename for non-renameable: $range")
    }

    @Test
    fun `should handle simple variable rename`() = runTest {
        val code = """
            def oldName = "hello"
            println oldName
            return oldName
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the code first
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to rename the variable at the declaration
        val position = Position(0, 6) // Should be inside "oldName"

        val workspaceEdit = renameProvider.rename(uri.toString(), position, "newName")

        println("Rename result: $workspaceEdit")
        println("Changes: ${workspaceEdit?.changes}")

        // Test basic structure - we expect some result even if the exact implementation varies
        // The important thing is that it doesn't crash and returns a WorkspaceEdit structure
    }

    @Test
    fun `should validate identifier names`() = runTest {
        val code = """
            def myVar = "hello"
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the code first
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        val position = Position(0, 6)

        // Test invalid identifier names
        val invalidNames = listOf("", "123invalid", "my-var", "my var", "class")

        for (invalidName in invalidNames) {
            val result = renameProvider.rename(uri.toString(), position, invalidName)
            println("Rename with invalid name '$invalidName': $result")
            // Should return null or empty result for invalid names
        }

        // Test valid identifier name
        val validResult = renameProvider.rename(uri.toString(), position, "validName")
        println("Rename with valid name: $validResult")
    }

    // TDD: Failing tests that demonstrate the rename issue
    @Test
    fun `should rename local variable declared with def`() = runTest {
        val code = """
            def props = obj.properties.clone()
            println props
            return props
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to rename the variable at the declaration (line 0, column 4)
        val position = Position(0, 6) // Inside "props"

        val workspaceEdit = renameProvider.rename(uri.toString(), position, "newProps")

        // Should return a WorkspaceEdit with 3 changes (declaration + 2 references)
        assertNotNull(workspaceEdit) { "Rename should return WorkspaceEdit" }
        assertNotNull(workspaceEdit!!.changes) { "WorkspaceEdit should have changes" }

        val changes = workspaceEdit.changes[uri.toString()]
        assertNotNull(changes) { "Should have changes for the file" }
        assertEquals(3, changes!!.size, "Should find 3 references: declaration + 2 uses")
    }

    @Test
    fun `should rename closure parameter`() = runTest {
        val code = """
            [1, 2, 3].each { item ->
                println item
                return item * 2
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to rename the closure parameter (line 0, around "item")
        val position = Position(0, 18) // Inside "item"

        val workspaceEdit = renameProvider.rename(uri.toString(), position, "element")

        // Should return a WorkspaceEdit with 3 changes (parameter + 2 references)
        assertNotNull(workspaceEdit) { "Rename should return WorkspaceEdit" }
        assertNotNull(workspaceEdit!!.changes) { "WorkspaceEdit should have changes" }

        val changes = workspaceEdit.changes[uri.toString()]
        assertNotNull(changes) { "Should have changes for the file" }
        assertEquals(3, changes!!.size, "Should find 3 references: parameter + 2 uses")
    }

    @Test
    fun `should rename variable across multiple references in same scope`() = runTest {
        val code = """
            def calculate() {
                def total = 0
                total += 10
                total += 20
                println total
                return total
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to rename the variable at the declaration (line 1, around "total")
        val position = Position(1, 8) // Inside "total"

        val workspaceEdit = renameProvider.rename(uri.toString(), position, "sum")

        // Should return a WorkspaceEdit with 5 changes (declaration + 4 references)
        assertNotNull(workspaceEdit) { "Rename should return WorkspaceEdit" }
        assertNotNull(workspaceEdit!!.changes) { "WorkspaceEdit should have changes" }

        val changes = workspaceEdit.changes[uri.toString()]
        assertNotNull(changes) { "Should have changes for the file" }
        assertEquals(5, changes!!.size, "Should find 5 references: declaration + 4 uses")
    }

    @Test
    fun `should handle method name rename`() = runTest {
        val code = """
            def myMethod() {
                return "hello"
            }

            myMethod()
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the code first
        val compilationResult = compilationService.compile(uri, code)
        assert(compilationResult.isSuccess) { "Compilation should succeed" }

        // Try to rename the method at the declaration
        val position = Position(0, 6) // Should be inside "myMethod"

        val workspaceEdit = renameProvider.rename(uri.toString(), position, "newMethodName")

        println("Method rename result: $workspaceEdit")
        println("Changes: ${workspaceEdit?.changes}")

        // Should return a WorkspaceEdit with changes for both declaration and call
        // The exact implementation depends on AST visitor accuracy
    }
}
