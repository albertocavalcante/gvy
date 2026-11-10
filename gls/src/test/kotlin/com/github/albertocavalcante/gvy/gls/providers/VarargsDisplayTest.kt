package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `String... parts` reads as written everywhere; an explicit `String[] parts` stays an array (#1405). */
class VarargsDisplayTest {
    private val compilation = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())
    private val uri = URI.create("file:///Logs.groovy")
    private val source = """
        class Logs {
            def log(String... parts) {}
            def raw(String[] parts) {}
            def run() {
                log('a', 'b')
            }
        }
    """.trimIndent()

    @Test
    fun `member completion shows varargs and arrays as written`(): Unit = runBlocking {
        val items = complete("$source\nnew Logs().")

        assertEquals(
            "log(\${1:String... parts})",
            items.single {
                it.label == "log" && "parts" in it.detail.orEmpty()
            }.insertText,
        )
        assertEquals("raw(\${1:String[] parts})", items.single { it.label == "raw" }.insertText)
    }

    @Test
    fun `in-scope completion shows varargs as written`(): Unit = runBlocking {
        val text = "class Logs {\n  def log(String... parts) {}\n  def m() { lo }\n}"

        val items = CompletionProvider.getContextualCompletions(
            uri.toString(),
            2,
            text.lines()[2].indexOf(" }"),
            compilation,
            resolver,
            text,
        )

        assertEquals(
            "log(\${1:String... parts})",
            items.single {
                it.label == "log" && "parts" in it.detail.orEmpty()
            }.insertText,
        )
    }

    @Test
    fun `hover and signature help show varargs as written`(): Unit = runBlocking {
        documents.put(uri, source, 1)
        assertTrue(compilation.compile(uri, source).isSuccess)

        val hover = assertNotNull(
            HoverProvider(compilation, documents, HoverContentGenerator(resolver))
                .provideHover(uri.toString(), Position(1, source.lines()[1].indexOf("log") + 1)),
        ).contents.right.value
        assertTrue("log(String... parts)" in hover, hover)

        val call = source.lines().indexOfFirst { "log('a'" in it }
        val help = SignatureHelpProvider(compilation, documents, resolver)
            .provideSignatureHelp(uri.toString(), Position(call, source.lines()[call].indexOf("log(") + 4))
        val signature = help.signatures.single { "parts" in it.label }
        assertEquals(listOf("String... parts"), signature.parameters.mapNotNull { it.label?.left })
    }

    private suspend fun complete(text: String): List<CompletionItem> {
        val lines = text.lines()
        return CompletionProvider.getContextualCompletions(
            uri.toString(),
            lines.lastIndex,
            lines.last().length,
            compilation,
            resolver,
            text,
        )
    }
}
