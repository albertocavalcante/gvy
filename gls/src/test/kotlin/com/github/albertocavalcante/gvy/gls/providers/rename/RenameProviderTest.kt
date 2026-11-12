// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
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
        renameProvider = RenameProvider(compilationService) { documentProvider.get(it) }
    }

    @Test
    fun `rename local variable updates all references`(): Unit = runBlocking {
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

    /**
     * Applies [edits] to [text]. Fails, listing what each edit would replace, if edits overlap
     * (LSP forbids that) or fall outside the text.
     */
    private fun applyEdits(text: String, edits: List<org.eclipse.lsp4j.TextEdit>): String {
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        fun offset(p: Position) = lineStarts.getOrNull(p.line)?.plus(p.character) ?: Int.MAX_VALUE
        val spans = edits.map { offset(it.range.start) to offset(it.range.end) }.sortedBy { it.first }
        val described = spans.joinToString("; ") { (from, to) ->
            if (to <=
                text.length
            ) {
                "[$from,$to)=" + text.substring(from, to).replace("\n", "\\n")
            } else {
                "[$from,$to)=<out of range>"
            }
        }
        check(spans.all { it.second <= text.length } && spans.zipWithNext().all { (a, b) -> a.second <= b.first }) {
            "Edits overlap or exceed the text: $described"
        }
        return spans.sortedByDescending { it.first }.fold(text) { acc, (from, to) ->
            acc.substring(0, from) + edits.first { offset(it.range.start) == from }.newText + acc.substring(to)
        }
    }

    private suspend fun renameAndApply(content: String, position: Position, newName: String): String {
        val uri = workspaceRoot.resolve("Rename.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)
        val edits = renameProvider.provideRename(uri, position, newName).documentChanges
            .filter { it.isLeft && it.left.textDocument.uri == uri }
            .flatMap { it.left.edits }
        return applyEdits(content, edits)
    }

    @Test
    fun `renaming a method from its declaration changes only the name and its calls`(): Unit = runBlocking {
        val content = """
            class Service {
                void performAction() {
                    println "Action"
                }
                void run() {
                    performAction()
                }
            }
        """.trimIndent()

        val result = renameAndApply(content, Position(1, 12), "doSomething")

        assertEquals(content.replace("performAction", "doSomething"), result)
    }

    @Test
    fun `renaming a method leaves same-named calls on other types alone`(): Unit = runBlocking {
        val content = """
            class Service {
                void size() {}
                void run() {
                    size()
                    [1, 2].size()
                    "abc".size()
                }
            }
        """.trimIndent()

        val result = renameAndApply(content, Position(1, 10), "count")

        assertEquals(
            content.replaceFirst("void size()", "void count()").replaceFirst("        size()", "        count()"),
            result,
        )
    }

    @Test
    fun `renaming a field leaves the same word in its annotation string alone`(): Unit = runBlocking {
        val content = """
            class Dto {
                @SuppressWarnings("id") Long id
                Long next() { return id }
            }
        """.trimIndent()

        val result = renameAndApply(content, Position(1, 33), "key")

        assertEquals(
            content.replace("Long id", "Long key").replace("return id", "return key"),
            result,
        )
    }

    @Test
    fun `renaming a method leaves the same word in its annotation argument alone`(): Unit = runBlocking {
        val content = """
            class A {
                @Deprecated(since = "foo") void foo() {}
                void run() { foo() }
            }
        """.trimIndent()

        val result = renameAndApply(content, Position(1, 37), "bar")

        assertEquals(content.replace("void foo()", "void bar()").replace("{ foo() }", "{ bar() }"), result)
    }

    @Test
    fun `renaming a class from its declaration changes only the name and its uses`(): Unit = runBlocking {
        val content = """
            class Service {
                String name
            }
            def s = new Service()
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 7), "Worker")

        assertEquals(content.replace("Service", "Worker"), result)
    }

    @Test
    fun `renaming a field changes only the name and its uses`(): Unit = runBlocking {
        val content = """
            class Holder {
                String label
                String show() { return label }
            }
        """.trimIndent()

        val result = renameAndApply(content, Position(1, 12), "title")

        assertEquals(content.replace("label", "title"), result)
    }

    @Test
    fun `rename with keyword should fail`(): Unit = runBlocking {
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
    fun `rename with same name should fail`(): Unit = runBlocking {
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
    fun `rename with empty name should fail`(): Unit = runBlocking {
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
    fun `rename with invalid identifier should fail`(): Unit = runBlocking {
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
    fun `rename method updates all calls`(): Unit = runBlocking {
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
    fun `rename class property updates all accesses`(): Unit = runBlocking {
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
    fun `rename top-level class includes file rename`(): Unit = runBlocking {
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
    fun `rename parameter in method signature and body`(): Unit = runBlocking {
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
    fun `rename at position with no symbol should fail`(): Unit = runBlocking {
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
    fun `rename class in file with different name should not include file rename`(): Unit = runBlocking {
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
    fun `rename with whitespace-only name should fail`(): Unit = runBlocking {
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
    fun `rename with special characters should fail`(): Unit = runBlocking {
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
