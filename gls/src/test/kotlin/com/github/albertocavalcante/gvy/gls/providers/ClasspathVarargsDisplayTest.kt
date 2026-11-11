package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.MethodCallMetadataResolver
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Classpath methods declared `Type...` read as written in completion, hover and signature help (#1418). */
class ClasspathVarargsDisplayTest {
    private val compilation = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())
    private val metadata = MethodCallMetadataResolver(compilation.classpathService, compilation.gdkProvider, resolver)
    private val uri = URI.create("file:///Fmt.groovy")
    private val source = """
        class Fmt {
            def run() {
                String.format('%s', 1)
            }
        }
    """.trimIndent()

    @Test
    fun `instance completion shows classpath varargs`(): Unit = runBlocking {
        val items = complete("'x'.")

        val formatted = items.filter { it.label == "formatted" }
        assertTrue(formatted.any { "Object..." in it.detail.orEmpty() }, "found ${formatted.map { it.detail }}")
    }

    @Test
    fun `hover shows classpath varargs`(): Unit = runBlocking {
        val (line, column) = compiled()

        val hover = assertNotNull(
            HoverProvider(compilation, documents, HoverContentGenerator(resolver, metadata))
                .provideHover(uri.toString(), Position(line, column + 1)),
        ).contents.right.value
        assertTrue("Object..." in hover && "Object[]" !in hover, hover)
    }

    @Test
    fun `signature help shows classpath varargs`(): Unit = runBlocking {
        val (line, _) = compiled()

        val help = SignatureHelpProvider(compilation, documents, resolver)
            .provideSignatureHelp(uri.toString(), Position(line, source.lines()[line].indexOf("1)")))
        val labels = help.signatures.map { it.label }
        assertTrue(labels.any { "Object..." in it } && labels.none { "Object[]" in it }, "found $labels")
    }

    private suspend fun compiled(): Pair<Int, Int> {
        documents.put(uri, source, 1)
        assertTrue(compilation.compile(uri, source).isSuccess)
        val line = source.lines().indexOfFirst { "String.format" in it }
        return line to source.lines()[line].indexOf("format")
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
