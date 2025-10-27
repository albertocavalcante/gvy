package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertTrue

class RenameProviderTest {

    private val compilationService = GroovyCompilationService()
    private val documentProvider = DocumentProvider()
    private val renameProvider = RenameProvider(compilationService, documentProvider)

    @Test
    fun `rename local variable updates all references`() = runTest {
        val uri = java.net.URI.create("file:///Rename.groovy")
        val source = """
            class Sample {
                def run() {
                    def value = 1
                    def copy = value + value
                }
            }
        """.trimIndent()

        documentProvider.put(uri, source)
        compilationService.compile(uri, source)

        val renamePosition = positionOf(source, "value = 1")
        val workspaceEdit = renameProvider.rename(
            uri.toString(),
            renamePosition,
            "total",
        )

        val edits = workspaceEdit.changes?.get(uri.toString())
        assertTrue(edits != null && edits.size == 3, "Should update declaration and two references")
        assertTrue(edits!!.all { it.newText == "total" }, "All edits should use new name")
    }
}

private fun positionOf(source: String, snippet: String): Position {
    val lines = source.lines()
    lines.forEachIndexed { index, line ->
        val column = line.indexOf(snippet)
        if (column >= 0) {
            return Position(index, column)
        }
    }
    error("Snippet '$snippet' not found in source")
}
