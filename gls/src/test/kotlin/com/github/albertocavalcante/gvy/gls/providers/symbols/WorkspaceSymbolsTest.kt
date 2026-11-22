// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.services.GroovyWorkspaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("DEPRECATION") // SymbolInformation is what workspace/symbol answers with
class WorkspaceSymbolsTest {
    @TempDir
    lateinit var tempDir: Path

    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(documentProvider = documents)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val textService = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )
    private val workspace = GroovyWorkspaceService(
        compilation,
        compilation.symbolIndexingService,
        scope,
        textDocumentService = textService,
    )

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.shutdown()
    }

    private fun search(query: String): List<SymbolInformation> =
        workspace.symbol(WorkspaceSymbolParams(query)).get(WAIT_SECONDS, TimeUnit.SECONDS).left

    private fun indexFile(name: String, text: String) {
        val file = Files.writeString(tempDir.resolve(name), text)
        runBlocking { compilation.symbolIndexingService.indexFile(file.toUri()) }
    }

    @Test
    fun `nested classes use source names with their outer class as container`() {
        indexFile(
            "Outers.groovy",
            """
            package demo.nesting
            class Outer2 {
                static class Inner { String greet(String who) { "hi" } }
                class Counter { int count }
                Runnable task() { new Runnable() { void run() {} } }
            }
            """.trimIndent(),
        )

        val results = search("Outer2") + search("Inner") + search("Counter")

        val names = results.map { it.name }
        assertTrue("Outer2" in names && "Inner" in names && "Counter" in names, "source names expected: $names")
        assertTrue(names.none { '$' in it }, "binary names leak: $names")
        assertEquals("demo.nesting.Outer2", results.single { it.name == "Inner" }.containerName)
        assertEquals(SymbolKind.Class, results.single { it.name == "Counter" }.kind)
    }

    @Test
    fun `imports are not symbols for a query either`() {
        indexFile("Outers.groovy", "package demo\nclass Outer2 {}\n")
        indexFile("Tour.groovy", "import demo.Outer2\nclass Tour { def o = new Outer2() }\n")

        val results = search("Outer2")

        assertEquals(listOf("Outer2"), results.map { it.name })
        assertTrue(results.single().location.uri.endsWith("Outers.groovy"))
    }

    @Test
    fun `symbols of a buffer that was just opened are found without waiting for a diagnostics pass`() {
        val uri = URI.create("file:///workspace/src/Symbols.groovy")
        compilation.onDocumentOpened(uri)
        documents.put(uri, "class UniqueController {\n    void uniqueMethod() {}\n}\n", 1)

        val names = search("Unique").map { it.name }

        assertEquals(listOf("UniqueController", "uniqueMethod"), names.sorted())
    }

    @Test
    fun `ranges are UTF-16 for symbols of closed files`() {
        val line = "    String s = \"😀\"; void hello() {}"
        indexFile("Emoji.groovy", "class Emoji {\n$line\n}\n")

        val hello = search("hello").single()

        assertEquals(line.indexOf("void"), hello.location.range.start.character)
    }

    private companion object {
        const val WAIT_SECONDS = 20L
    }
}
