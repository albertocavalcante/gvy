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

    @Test
    fun `a local class named Specification gets no table action even with a spock star import`(): Unit = runBlocking {
        val source = """
                import spock.lang.*
                class Specification {}
                class Rule extends Specification {
                    def check() {
                        expect:
                        true
                        where:
                        a | b
                        100|2
                    }
                }
        """.trimIndent()
        documents.put(uri, source, 1)
        compilationService.compile(uri, source)

        val actions = provider.provideCodeActions(params(line = 7))

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `an uncertain external base gets no table action`(): Unit = runBlocking {
        val source = content.replace("spock.lang.Specification", "BaseSpec")
        documents.put(uri, source, 1)
        compilationService.compile(uri, source)

        val actions = provider.provideCodeActions(params())

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `an uncertain class gets no table action because the edit changes source`(): Unit = runBlocking {
        // Without Spock on the compile classpath, `import spock.lang.*` alone cannot prove the base.
        val withoutSpock = object : ClassLoader(SpockTableProviderTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("spock.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }

            override fun getResource(name: String) = if (name.startsWith("spock/")) null else super.getResource(name)
        }
        val service = GroovyCompilationService(withoutSpock)
        val source = "import spock.lang.*\n" +
            content.replace("class TableSpec extends spock.lang.Specification", "class TableSpec extends Specification")
        val starProvider = CodeActionProvider(
            service,
            documents,
            object : Formatter {
                override fun format(text: String): String = text
            },
        )
        try {
            documents.put(uri, source, 1)
            service.compile(uri, source)

            val actions = starProvider.provideCodeActions(params(line = 6))

            assertTrue(actions.none { it.title == "Align Spock data table" })
        } finally {
            service.clearCaches()
        }
    }

    private fun params(line: Int = 5) = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        range = Range(Position(line, 8), Position(line, 8))
        context = CodeActionContext(emptyList())
    }
}
