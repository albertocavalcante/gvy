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

    /** Only the quick fixes: Organize Imports may be offered next to them and is covered by its own test. */
    private fun quickFixes(diagnostics: List<Diagnostic>, only: List<String>? = null): List<CodeAction> =
        actions(diagnostics, only).filter { it.kind == CodeActionKind.QuickFix }

    private fun CodeAction.textEdits(): List<TextEdit> = edit.documentChanges.single().left.edits

    private fun apply(text: String, edits: List<TextEdit>): String {
        val index = Utf16LineIndex(text)
        fun offset(position: Position) = index.offsetOf(position.line, position.character)
        var result = text
        for (edit in edits.sortedByDescending { offset(it.range.start) }) {
            result = result.substring(0, offset(edit.range.start)) + edit.newText +
                result.substring(offset(edit.range.end))
        }
        return result
    }

    @Test
    fun `offers a versioned quickfix that removes the unused import`() {
        val content = "import java.util.ArrayList\nimport java.util.HashMap\n\nArrayList list = new ArrayList()\n"
        val diagnostics = open(content, version = 7)

        val action = quickFixes(diagnostics).single()

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

        val actions = quickFixes(diagnostics)

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
            val action = quickFixes(open(content)).single()

            val expected = listOf("import java.util.HashMap", "", "HashMap m = null", "").joinToString(eol)
            assertEquals(expected, apply(content, action.textEdits()), "eol=${eol.replace("\r", "CR")}")
        }
    }

    @Test
    fun `is only offered for quickfix requests`() {
        val diagnostics = open("import java.util.ArrayList\nint x = 1\n")

        assertEquals(1, quickFixes(diagnostics, only = listOf(CodeActionKind.QuickFix)).size)
        assertEquals(1, quickFixes(diagnostics, only = null).size)
        assertTrue(actions(diagnostics, only = listOf(CodeActionKind.Refactor)).isEmpty())
        assertTrue(quickFixes(diagnostics, only = listOf(CodeActionKind.SourceOrganizeImports)).isEmpty())
    }

    @Test
    fun `the detector reports nothing for used, starred or annotated imports, so no fix is offered`() {
        val content = """
            @Grab('g:a:1')
            import x.Directive
            import java.util.*
            import static java.lang.Math.*
            import java.util.ArrayList

            ArrayList list = new ArrayList()
        """.trimIndent()
        val diagnostics = open(content)

        assertEquals(emptyList(), diagnostics.map { it.message })
        assertTrue(quickFixes(diagnostics).isEmpty())
    }

    @Test
    fun `refuses a fix that the planner cannot prove safe even when gvy reported the import`() {
        val content = "import java.util.HashMap // used by the build\nint x = 1\n"
        val diagnostics = open(content)

        assertEquals(1, diagnostics.size)
        assertTrue(quickFixes(diagnostics).isEmpty())
    }

    @Test
    fun `keeps an unused import whose name is still mentioned in Groovydoc`() {
        val content = "import java.util.HashMap\n\n/** See {@link HashMap}. */\nclass A {}\n"
        val diagnostics = open(content)

        assertEquals(1, diagnostics.size, "gvy still reports the import")
        assertTrue(quickFixes(diagnostics).isEmpty())
    }

    @Test
    fun `removes unused aliased and static imports, and keeps used nested and static ones`() {
        val content = listOf(
            "import java.util.Map.Entry",
            "import java.util.concurrent.TimeUnit as TU",
            "import static java.lang.Math.max",
            "import static java.lang.Math.min",
            "",
            "Entry e = null",
            "def x = max(1, 2)",
            "",
        ).joinToString("\n")
        val diagnostics = open(content)

        assertEquals(2, diagnostics.size, diagnostics.joinToString { it.message })
        val all = quickFixes(diagnostics).single { it.title == "Remove all unused imports" }
        val expected = "import java.util.Map.Entry\nimport static java.lang.Math.max\n\n" +
            "Entry e = null\ndef x = max(1, 2)\n"
        assertEquals(expected, apply(content, all.textEdits()))
    }

    @Test
    fun `offers remove-all for adjacent unused imports and removes them cleanly`() {
        val content = "import java.util.ArrayList\nimport java.util.HashMap\n\nint x = 1\n"
        val diagnostics = open(content)

        val all = quickFixes(diagnostics).single { it.title == "Remove all unused imports" }

        assertEquals("int x = 1\n", apply(content, all.textEdits()))
    }

    @Test
    fun `withholds the fix when the buffer moved on since the compile`() {
        val content = "import java.util.ArrayList\nint x = 1\n"
        val diagnostics = open(content)
        assertNotNull(documents.update(uri, "int y = 2\n$content", 4))

        assertTrue(quickFixes(diagnostics).isEmpty())
    }

    @Test
    fun `offers nothing on a file with syntax errors`() {
        val content = "import java.util.ArrayList\nclass A { void m( }\n"
        open(content)
        val stale = Diagnostic(Range(Position(0, 0), Position(0, 26)), "Unused import: java.util.ArrayList").apply {
            code = Either.forLeft("unused-import")
            source = "Groovy"
        }

        assertTrue(quickFixes(listOf(stale)).isEmpty())
    }

    @Test
    fun `ignores a diagnostic with another code`() {
        val content = "import java.util.ArrayList\nint x = 1\n"
        open(content)
        val other = Diagnostic(Range(Position(0, 0), Position(0, 26)), "Unused import").apply {
            code = Either.forLeft("some-other-code")
        }

        assertTrue(quickFixes(listOf(other)).isEmpty())
    }
}
