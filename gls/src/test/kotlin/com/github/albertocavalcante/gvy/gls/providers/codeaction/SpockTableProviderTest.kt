package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.Formatter
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpockTableProviderTest {
    private val uri = URI.create("file:///TableSpec.groovy")
    private val compilationService = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val provider = CodeActionProvider(
        compilationService,
        documents,
        object : Formatter {
            override fun format(text: String): String = text
        },
    )
    private val content = """
        class TableSpec extends spock.lang.Specification {
            def "feature"() {
                expect:
                true
                where:
                a | b
                100|2
            }
        }
    """.trimIndent()

    @AfterEach
    fun clearCaches() {
        compilationService.clearCaches()
    }

    @Test
    fun `default compilation produces a versioned table action`(): Unit = runBlocking {
        documents.put(uri, content, 7)
        compilationService.compile(uri, content)

        val action = provider.provideCodeActions(params()).single { it.title == "Align Spock data table" }
        val documentEdit = action.edit.documentChanges.single().left
        assertEquals(uri.toString(), documentEdit.textDocument.uri)
        assertEquals(7, documentEdit.textDocument.version)
        assertEquals(listOf("a   | b", "100 | 2"), documentEdit.edits.map { it.newText })
    }

    @Test
    fun `withholds table action when current document differs from cached compilation`(): Unit = runBlocking {
        documents.put(uri, content, 7)
        compilationService.compile(uri, content)
        assertNotNull(documents.update(uri, content.replace("100", "1000"), 8))

        val actions = provider.provideCodeActions(params())

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `withholds table action when client requests quick fixes`(): Unit = runBlocking {
        documents.put(uri, content, 7)
        compilationService.compile(uri, content)
        val params = params().apply { context.only = listOf(CodeActionKind.QuickFix) }

        val actions = provider.provideCodeActions(params)

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    private fun params() = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        range = Range(Position(5, 8), Position(5, 8))
        context = CodeActionContext(emptyList())
    }
}
