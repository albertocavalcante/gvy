// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the real service: the semantic index must cover every workspace file, not only the compiled ones. */
class WorkspaceSemanticCoverageTest {
    @TempDir
    lateinit var tempDir: Path

    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(documentProvider = documents)
    private val index get() = checkNotNull(service.getWorkspaceSymbolIndex())

    private fun write(name: String, content: String): URI {
        val file = tempDir.resolve("src").resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file.toUri()
    }

    private fun workspaceWithCaller(): Pair<URI, URI> {
        val a = write("A.groovy", "class A {\n    def greet() { 'hi' }\n}\n")
        val b = write("B.groovy", "class B {\n    def run() { new A().greet() }\n}\n")
        service.workspaceManager.initializeWorkspace(tempDir)
        return a to b
    }

    private fun greetId(a: URI): String = checkNotNull(index.getDocument(a)).symbols.first { it.name == "greet" }.symbol

    private fun referencingFiles(symbolId: String): Set<String> = index.findReferences(symbolId).map { it.uri }.toSet()

    @Test
    fun `a workspace compile alone makes cross-file references findable`(): Unit = runBlocking {
        val (a, b) = workspaceWithCaller()

        service.getWorkspaceCompiler().compileWorkspace()

        assertNotNull(index.getDocument(a), "A is indexed")
        assertTrue(b.toString() in referencingFiles(greetId(a)), "the call in B is found")
    }

    @Test
    fun `deleting a file invalidates it and removes its occurrences`(): Unit = runBlocking {
        val (a, b) = workspaceWithCaller()
        service.getWorkspaceCompiler().compileWorkspace()
        val id = greetId(a)

        Files.delete(Path.of(b))
        service.invalidateCache(b)

        assertNull(index.getDocument(b))
        assertTrue(b.toString() !in referencingFiles(id))
    }

    @Test
    fun `clearCaches removes every semantic entry`(): Unit = runBlocking {
        val (a, b) = workspaceWithCaller()
        service.getWorkspaceCompiler().compileWorkspace()
        assertNotNull(index.getDocument(a))

        service.clearCaches()

        assertNull(index.getDocument(a))
        assertNull(index.getDocument(b))
    }

    @Test
    fun `a workspace compile does not replace the document of an open buffer`(): Unit = runBlocking {
        val (a, b) = workspaceWithCaller()
        val buffer = "class B {\n    def fromBuffer() { new A().greet() }\n}\n"
        documents.put(b, buffer)
        service.compile(b, buffer)

        service.getWorkspaceCompiler().compileWorkspace()

        val names = checkNotNull(index.getDocument(b)).symbols.map { it.name }
        assertTrue("fromBuffer" in names, "buffer symbols kept: $names")
        assertTrue("run" !in names, "disk symbols not applied: $names")
        assertNotNull(index.getDocument(a), "other files are still covered")
    }

    @Test
    fun `closing a buffer that differed from disk re-derives the document from disk`(): Unit = runBlocking {
        val (_, b) = workspaceWithCaller()
        val buffer = "class B {\n    def fromBuffer() {}\n}\n"
        documents.put(b, buffer)
        service.compile(b, buffer)
        documents.remove(b)

        service.refreshSemanticFromDisk(listOf(b))

        val names = checkNotNull(index.getDocument(b)).symbols.map { it.name }
        assertEquals(true, "run" in names && "fromBuffer" !in names, "disk symbols restored: $names")
    }

    @Test
    fun `closing an untitled buffer drops its document`(): Unit = runBlocking {
        val untitled = URI.create("untitled:Scratch-1")
        val buffer = "class Scratch {}"
        documents.put(untitled, buffer)
        service.compile(untitled, buffer)
        assertNotNull(index.getDocument(untitled))
        documents.remove(untitled)

        service.refreshSemanticFromDisk(listOf(untitled))

        assertNull(index.getDocument(untitled))
    }

    @Test
    fun `a file changed on disk with nothing open gets a new document`(): Unit = runBlocking {
        val (a, b) = workspaceWithCaller()
        service.getWorkspaceCompiler().compileWorkspace()
        val id = greetId(a)
        assertTrue(b.toString() in referencingFiles(id))

        Files.writeString(Path.of(b), "class B {\n    def run() { 'no call' }\n}\n")
        service.refreshSemanticFromDisk(listOf(b))

        assertTrue(b.toString() !in referencingFiles(id), "the removed call is gone")
    }

    @Test
    fun `a file that vanishes mid-batch is dropped without stopping the others`(): Unit = runBlocking {
        val (a, b) = workspaceWithCaller()
        service.getWorkspaceCompiler().compileWorkspace()
        val id = greetId(a)
        Files.delete(Path.of(a))
        Files.writeString(Path.of(b), "class B {\n    def run() { 'no call' }\n}\n")

        service.refreshSemanticFromDisk(listOf(a, b))

        assertNull(index.getDocument(a))
        assertTrue(b.toString() !in referencingFiles(id), "B was still refreshed")
    }
}
