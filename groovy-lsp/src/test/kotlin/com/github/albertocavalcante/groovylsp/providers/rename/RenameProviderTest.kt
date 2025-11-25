package com.github.albertocavalcante.groovylsp.providers.rename

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class RenameProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider
    private lateinit var renameProvider: RenameProvider
    private lateinit var workspaceRoot: Path

    @BeforeEach
    fun setUp() {
        workspaceRoot = Files.createTempDirectory("groovy-lsp-rename-test")
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(workspaceRoot)
        documentProvider = DocumentProvider()
        renameProvider = RenameProvider(compilationService)
    }

    @Test
    fun `rename local variable updates all references`() = runBlocking {
        val content = """
            def localVar = 42
            println localVar
            localVar = 100
            return localVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Rename at declaration
        val workspaceEdit = renameProvider.provideRename(uri, Position(0, 8), "newVar")

        assertNotNull(workspaceEdit)
        assertNotNull(workspaceEdit.documentChanges)
        assertTrue(workspaceEdit.documentChanges.isNotEmpty())

        // Check that all edits are for the new name
        val textEdits = workspaceEdit.documentChanges
            .filter { it.isLeft }
            .flatMap { it.left.edits }

        assertTrue(textEdits.all { it.newText == "newVar" })
        assertTrue(textEdits.size >= 4, "Should rename declaration and all usages")
    }

    @Test
    fun `rename with keyword should fail`() = runBlocking {
        val content = """
            def myVar = 42
            println myVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 8), "class")
        }

        assertTrue(exception.responseError.message.contains("keyword"))
    }

    @Test
    fun `rename with same name should fail`() = runBlocking {
        val content = """
            def myVar = 42
            println myVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 8), "myVar")
        }

        assertTrue(exception.responseError.message.contains("same as the current name"))
    }

    @Test
    fun `rename with empty name should fail`() = runBlocking {
        val content = """
            def myVar = 42
            println myVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 8), "")
        }

        assertTrue(exception.responseError.message.contains("empty"))
    }

    @Test
    fun `rename with invalid identifier should fail`() = runBlocking {
        val content = """
            def myVar = 42
            println myVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 8), "123invalid")
        }

        assertTrue(exception.responseError.message.contains("not a valid identifier"))
    }

    @Test
    fun `rename method updates all calls`() = runBlocking {
        val content = """
            def oldMethod() {
                return 42
            }
            
            def caller() {
                oldMethod()
                return oldMethod()
            }
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Rename at method declaration
        val workspaceEdit = renameProvider.provideRename(uri, Position(0, 8), "newMethod")

        assertNotNull(workspaceEdit)
        val textEdits = workspaceEdit.documentChanges
            .filter { it.isLeft }
            .flatMap { it.left.edits }

        assertTrue(textEdits.all { it.newText == "newMethod" })
        assertTrue(textEdits.size >= 3, "Should rename declaration and all calls")
    }

    @Test
    fun `rename class property updates all accesses`() = runBlocking {
        val content = """
            class MyClass {
                String oldProp = "value"
                
                def useIt() {
                    println oldProp
                    this.oldProp = "new"
                }
            }
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Rename at property declaration
        val workspaceEdit = renameProvider.provideRename(uri, Position(1, 15), "newProp")

        assertNotNull(workspaceEdit)
        val textEdits = workspaceEdit.documentChanges
            .filter { it.isLeft }
            .flatMap { it.left.edits }

        assertTrue(textEdits.all { it.newText == "newProp" })
        assertTrue(textEdits.isNotEmpty(), "Should rename property declaration and accesses")
    }

    @Test
    fun `rename top-level class includes file rename`() = runBlocking {
        val content = """
            class MyOldClass {
                def myMethod() {
                    return 42
                }
            }
        """.trimIndent()

        // Create a file with the same name as the class
        val fileName = "MyOldClass.groovy"
        val filePath = workspaceRoot.resolve(fileName)
        Files.writeString(filePath, content)

        val uri = filePath.toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Rename at class name
        val workspaceEdit = renameProvider.provideRename(uri, Position(0, 10), "MyNewClass")

        assertNotNull(workspaceEdit)
        assertNotNull(workspaceEdit.documentChanges)

        // Check for text edits
        val textEditChanges = workspaceEdit.documentChanges.filter { it.isLeft }
        assertTrue(textEditChanges.isNotEmpty(), "Should have text edits")

        // Check for file rename operation
        val resourceOperations = workspaceEdit.documentChanges.filter { it.isRight }
        assertEquals(1, resourceOperations.size, "Should have exactly one file rename operation")

        val renameFileOp = resourceOperations.first().right as org.eclipse.lsp4j.RenameFile
        assertTrue(renameFileOp.oldUri.endsWith("MyOldClass.groovy"))
        assertTrue(renameFileOp.newUri.endsWith("MyNewClass.groovy"))
    }

    @Test
    fun `rename parameter in method signature and body`() = runBlocking {
        val content = """
            def myMethod(String param1, int param2) {
                println param1
                param2 = param2 + 1
                return param1 + param2
            }
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Rename param1
        val workspaceEdit = renameProvider.provideRename(uri, Position(0, 24), "newParam")

        assertNotNull(workspaceEdit)
        val textEdits = workspaceEdit.documentChanges
            .filter { it.isLeft }
            .flatMap { it.left.edits }

        assertTrue(textEdits.all { it.newText == "newParam" })
        assertTrue(textEdits.size >= 3, "Should rename parameter in signature and all usages in body")
    }

    @Test
    fun `rename at position with no symbol should fail`() = runBlocking {
        val content = """
            // This is just a comment
            def myVar = 42
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Try to rename at the comment position (line 0, position in comment)
        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 5), "newName")
        }

        assertTrue(
            exception.responseError.message.contains("No symbol") ||
                exception.responseError.message.contains("Could not resolve"),
        )
    }

    @Test
    fun `rename class in file with different name should not include file rename`() = runBlocking {
        val content = """
            class SomeClass {
                def method() { return 1 }
            }
        """.trimIndent()

        // File name does NOT match class name
        val fileName = "DifferentFileName.groovy"
        val filePath = workspaceRoot.resolve(fileName)
        Files.writeString(filePath, content)

        val uri = filePath.toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        // Rename the class
        val workspaceEdit = renameProvider.provideRename(uri, Position(0, 10), "RenamedClass")

        assertNotNull(workspaceEdit)
        assertNotNull(workspaceEdit.documentChanges)

        // Check that text edits exist
        val textEditChanges = workspaceEdit.documentChanges.filter { it.isLeft }
        assertTrue(textEditChanges.isNotEmpty(), "Should have text edits for class rename")

        // Verify NO file rename operation when file name doesn't match class name
        val resourceOperations = workspaceEdit.documentChanges.filter { it.isRight }
        assertTrue(
            resourceOperations.isEmpty(),
            "Class rename should NOT include file rename when file name doesn't match class name",
        )
    }

    @Test
    fun `rename with whitespace-only name should fail`() = runBlocking {
        val content = """
            def myVar = 42
            println myVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 8), "   ")
        }

        assertTrue(
            exception.responseError.message.contains("empty") || exception.responseError.message.contains("blank"),
        )
    }

    @Test
    fun `rename with special characters should fail`() = runBlocking {
        val content = """
            def myVar = 42
            println myVar
        """.trimIndent()

        val uri = workspaceRoot.resolve("test.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)

        val exception = assertThrows<ResponseErrorException> {
            renameProvider.provideRename(uri, Position(0, 8), "my-var")
        }

        assertTrue(exception.responseError.message.contains("not a valid identifier"))
    }
}
