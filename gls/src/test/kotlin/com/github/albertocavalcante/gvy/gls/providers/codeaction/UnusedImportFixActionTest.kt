// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDiagnosticProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.Formatter
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnusedImportFixActionTest {
    private val uri = URI.create("file:///Imports.groovy")
    private val compilationService = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val diagnosticProvider = UnusedImportDiagnosticProvider(compilationService)
    private val provider = CodeActionProvider(
        compilationService,
        documents,
        object : Formatter {
            override fun format(text: String): String = text
        },
    )

    @AfterEach
    fun clearCaches() {
        compilationService.clearCaches()
    }

    /** Opens [content] at [version], compiles it, and returns the diagnostics gvy would publish for it. */
    private fun open(content: String, version: Int = 3): List<Diagnostic> = runBlocking {
        documents.put(uri, content, version)
        compilationService.compile(uri, content)
        diagnosticProvider.provideDiagnostics(uri, content).toList()
    }

    private fun actions(diagnostics: List<Diagnostic>, only: List<String>? = null): List<CodeAction> = runBlocking {
        val params = CodeActionParams(
            TextDocumentIdentifier(uri.toString()),
            Range(Position(0, 0), Position(0, 0)),
            CodeActionContext(diagnostics).apply { this.only = only },
        )
        provider.provideCodeActions(params)
    }

    private fun CodeAction.textEdits(): List<TextEdit> = edit.documentChanges.single().left.edits

    private fun apply(text: String, edits: List<TextEdit>): String {
        val index = Utf16LineIndex(text)
        fun offset(position: Position) = index.offsetOf(position.line, position.character)
        return edits.sortedByDescending { offset(it.range.start) }.fold(text) { acc, edit ->
            acc.substring(0, offset(edit.range.start)) + edit.newText + acc.substring(offset(edit.range.end))
        }
    }

    @Test
    fun `offers a versioned quickfix that removes the unused import`() {
        val content = "import java.util.ArrayList\nimport java.util.HashMap\n\nArrayList list = new ArrayList()\n"
        val diagnostics = open(content, version = 7)

        val action = actions(diagnostics).single()

        assertEquals("Remove unused import", action.title)
        assertEquals(CodeActionKind.QuickFix, action.kind)
        assertEquals(diagnostics, action.diagnostics)
        val document = action.edit.documentChanges.single().left.textDocument
        assertEquals(uri.toString(), document.uri)
        assertEquals(7, document.version)
        val expected = "import java.util.ArrayList\n\nArrayList list = new ArrayList()\n"
        assertEquals(expected, apply(content, action.textEdits()))
    }

    @Test
    fun `offers remove-all when two imports are unused and fixes each one separately`() {
        val content = listOf(
            "import java.util.ArrayList",
            "import java.util.HashMap",
            "import java.util.TreeMap",
            "",
            "TreeMap m = null",
            "",
        ).joinToString("\n")
        val diagnostics = open(content)

        val actions = actions(diagnostics)

        assertEquals(
            listOf("Remove unused import", "Remove unused import", "Remove all unused imports"),
            actions.map { it.title },
        )
        val withoutFirst = "import java.util.HashMap\nimport java.util.TreeMap\n\nTreeMap m = null\n"
        assertEquals(withoutFirst, apply(content, actions[0].textEdits()))
        assertEquals("import java.util.TreeMap\n\nTreeMap m = null\n", apply(content, actions[2].textEdits()))
    }

    @Test
    fun `works on CRLF and bare CR files`() {
        for (eol in listOf("\r\n", "\r")) {
            val content = listOf("import java.util.ArrayList", "import java.util.HashMap", "", "HashMap m = null", "")
                .joinToString(eol)
            val action = actions(open(content)).single()

            val expected = listOf("import java.util.HashMap", "", "HashMap m = null", "").joinToString(eol)
            assertEquals(expected, apply(content, action.textEdits()), "eol=${eol.replace("\r", "CR")}")
        }
    }

    @Test
    fun `is only offered for quickfix requests`() {
        val diagnostics = open("import java.util.ArrayList\nint x = 1\n")

        assertEquals(1, actions(diagnostics, only = listOf(CodeActionKind.QuickFix)).size)
        assertEquals(1, actions(diagnostics, only = null).size)
        assertTrue(actions(diagnostics, only = listOf(CodeActionKind.Refactor)).isEmpty())
        assertTrue(actions(diagnostics, only = listOf(CodeActionKind.SourceOrganizeImports)).isEmpty())
    }

    @Test
    fun `offers nothing for imports that are used, starred or annotated`() {
        val content = """
            @Grab('g:a:1')
            import x.Directive
            import java.util.*
            import static java.lang.Math.*
            import java.util.ArrayList

            ArrayList list = new ArrayList()
        """.trimIndent()

        assertTrue(actions(open(content)).isEmpty())
    }

    @Test
    fun `keeps an unused import whose name is still mentioned in Groovydoc`() {
        val content = "import java.util.HashMap\n\n/** See {@link HashMap}. */\nclass A {}\n"
        val diagnostics = open(content)

        assertTrue(actions(diagnostics).isEmpty())
    }

    @Test
    fun `withholds the fix when the buffer moved on since the compile`() {
        val content = "import java.util.ArrayList\nint x = 1\n"
        val diagnostics = open(content)
        assertNotNull(documents.update(uri, "int y = 2\n$content", 4))

        assertTrue(actions(diagnostics).isEmpty())
    }

    @Test
    fun `offers nothing on a file with syntax errors`() {
        val content = "import java.util.ArrayList\nclass A { void m( }\n"
        open(content)
        val stale = Diagnostic(Range(Position(0, 0), Position(0, 26)), "Unused import: java.util.ArrayList").apply {
            code = Either.forLeft("unused-import")
        }

        assertTrue(actions(listOf(stale)).isEmpty())
    }

    @Test
    fun `ignores a diagnostic with another code`() {
        val content = "import java.util.ArrayList\nint x = 1\n"
        open(content)
        val other = Diagnostic(Range(Position(0, 0), Position(0, 26)), "Unused import").apply {
            code = Either.forLeft("some-other-code")
        }

        assertTrue(actions(listOf(other)).isEmpty())
    }
}
