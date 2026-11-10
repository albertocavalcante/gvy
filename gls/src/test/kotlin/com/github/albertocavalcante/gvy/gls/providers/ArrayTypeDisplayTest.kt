package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Array types read as source writes them (`String[]`, `int[][]`), never as JVM descriptors (`String;`, `[I`). */
class ArrayTypeDisplayTest {
    private val compilation = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())
    private val uri = URI.create("file:///Arrays.groovy")
    private val source = """
        class Names {
            String[] split(String[] items, int[][] grid) { items }
            def run() {
                split(null, null)
            }
        }
    """.trimIndent()

    @Test
    fun `signature help labels array parameters and return types with brackets`(): Unit = runBlocking {
        compile()
        val line = source.lines().indexOfFirst { it.contains("split(null") }
        val position = Position(line, source.lines()[line].indexOf("split(") + "split(".length)

        val help = SignatureHelpProvider(
            compilation,
            documents,
            resolver,
        ).provideSignatureHelp(uri.toString(), position)

        val signature = help.signatures.single { "items" in it.label } // GDK split overloads also apply
        assertEquals("String[] split(String[] items, int[][] grid)", signature.label)
        assertEquals(listOf("String[] items", "int[][] grid"), signature.parameters.mapNotNull { it.label?.left })
    }

    @Test
    fun `hover on a method declaration shows array types with brackets`(): Unit = runBlocking {
        compile()
        val position = Position(1, source.lines()[1].indexOf("split") + 1)

        val hover = assertNotNull(
            HoverProvider(
                compilation,
                documents,
                HoverContentGenerator(resolver),
            ).provideHover(uri.toString(), position),
        )

        val text = hover.contents.right.value
        assertTrue("String[] items" in text && "int[][] grid" in text, text)
        assertTrue("String;" !in text && "[I" !in text, text)
    }

    private suspend fun compile() {
        documents.put(uri, source, 1)
        assertTrue(compilation.compile(uri, source).isSuccess)
    }
}
