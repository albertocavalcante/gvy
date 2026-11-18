// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.Formatter
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizeImportsActionTest {
    private val uri = URI.create("file:///Organize.groovy")
    private val compilationService = GroovyCompilationService()
    private val documents = DocumentProvider()
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

    private fun open(content: String, version: Int = 3) = runBlocking {
        documents.put(uri, content, version)
        compilationService.compile(uri, content)
    }

    private fun actions(only: List<String>?): List<CodeAction> = runBlocking {
        val params = CodeActionParams(
            TextDocumentIdentifier(uri.toString()),
            Range(Position(0, 0), Position(0, 0)),
            CodeActionContext(emptyList()).apply { this.only = only },
        )
        provider.provideCodeActions(params)
    }

    private fun organizeAction(only: List<String>? = listOf(CodeActionKind.SourceOrganizeImports)): CodeAction? =
        actions(only).singleOrNull { it.kind == CodeActionKind.SourceOrganizeImports }

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

    private val unorganized = listOf(
        "import java.util.TreeMap",
        "import java.util.ArrayList",
        "import java.util.HashMap",
        "",
        "TreeMap t = null",
        "ArrayList a = null",
        "",
    )

    @Test
    fun `returns a versioned source organizeImports action that sorts and removes unused imports`() {
        val content = unorganized.joinToString("\n")
        open(content, version = 9)

        val action = assertNotNull(organizeAction())

        assertEquals("Organize imports", action.title)
        assertEquals("source.organizeImports", action.kind)
        val documentEdit = action.edit.documentChanges.single().left
        assertEquals(uri.toString(), documentEdit.textDocument.uri)
        assertEquals(9, documentEdit.textDocument.version)
        val expected = "import java.util.ArrayList\nimport java.util.TreeMap\n\nTreeMap t = null\nArrayList a = null\n"
        assertEquals(expected, apply(content, documentEdit.edits))
    }

    @Test
    fun `follows the requested kinds`() {
        open(unorganized.joinToString("\n"))

        assertNotNull(organizeAction(listOf(CodeActionKind.SourceOrganizeImports)))
        assertNotNull(organizeAction(listOf(CodeActionKind.Source)))
        assertNotNull(organizeAction(null))
        assertNull(organizeAction(listOf(CodeActionKind.QuickFix)))
        assertNull(organizeAction(listOf(CodeActionKind.Refactor)))
        assertTrue(actions(listOf(CodeActionKind.SourceOrganizeImports)).all { it.kind == "source.organizeImports" })
    }

    @Test
    fun `returns no action when the imports are already organized`() {
        open("import java.util.ArrayList\nimport java.util.TreeMap\n\nTreeMap t = null\nArrayList a = null\n")

        assertNull(organizeAction())
    }

    @Test
    fun `applying the action leaves nothing more to organize`() {
        val content = unorganized.joinToString("\n")
        open(content)
        val organized = apply(content, assertNotNull(organizeAction()).edit.documentChanges.single().left.edits)

        open(organized, version = 4)

        assertNull(organizeAction())
    }

    @Test
    fun `follows the line ending of the file`() {
        for (eol in listOf("\r\n", "\r")) {
            val content = unorganized.joinToString(eol)
            open(content)
            val action = assertNotNull(organizeAction())

            val expected = listOf(
                "import java.util.ArrayList",
                "import java.util.TreeMap",
                "",
                "TreeMap t = null",
                "ArrayList a = null",
                "",
            ).joinToString(eol)
            assertEquals(expected, apply(content, action.edit.documentChanges.single().left.edits))
        }
    }

    @Test
    fun `returns no action when the buffer moved on since the compile`() {
        open(unorganized.joinToString("\n"))
        assertNotNull(documents.update(uri, "import b.B\nimport a.A\n", 4))

        assertNull(organizeAction())
    }

    @Test
    fun `returns no action before the document was ever compiled`() {
        documents.put(uri, unorganized.joinToString("\n"), 1)

        assertNull(organizeAction())
    }

    @Test
    fun `sorts but removes nothing in a file with syntax errors`() {
        val content = "import java.util.TreeMap\nimport java.util.ArrayList\n\nclass A { void m( }\n"
        open(content)

        val action = assertNotNull(organizeAction())

        val expected = "import java.util.ArrayList\nimport java.util.TreeMap\n\nclass A { void m( }\n"
        assertEquals(expected, apply(content, action.edit.documentChanges.single().left.edits))
    }
}
