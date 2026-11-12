package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoverExperienceTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `default hover infers a def variable from the compiled source`(): Unit = runBlocking {
        val hover = hover("def count = 42", Position(0, 5))

        assertContains(hover.contents.right.value, "int count")
    }

    @Test
    fun `native hover uses boxed Integer arguments for list and map literal cards`(): Unit = runBlocking {
        val source = "def values = [1, 2, 3]\ndef lookup = [a: 1, b: 2]"

        val list = hover(source, Position(0, 5), EngineType.Native)
        val map = hover(source, Position(1, 5), EngineType.Native)

        assertContains(list.contents.right.value, "ArrayList<Integer> values")
        assertContains(map.contents.right.value, "LinkedHashMap<String, Integer> lookup")
    }

    @Test
    fun `native hover uses Number for mixed numeric collection values without claiming coercion`(): Unit = runBlocking {
        val source = "def values = [1, 2L]\ndef lookup = [a: 1, b: 2L]"

        val list = hover(source, Position(0, 5), EngineType.Native)
        val map = hover(source, Position(1, 5), EngineType.Native)

        assertContains(list.contents.right.value, "ArrayList<Number> values")
        assertContains(map.contents.right.value, "LinkedHashMap<String, Number> lookup")
    }

    @Test
    fun `native hover highlights the reference rather than its declaration`(): Unit = runBlocking {
        val hover = hover("def greeting = 'hello'\nprintln greeting", Position(1, 10), EngineType.Native)

        assertContains(hover.contents.right.value, "greeting")
        assertEquals(Range(Position(1, 8), Position(1, 16)), hover.range)
    }

    @Test
    fun `native hover ranges use UTF16 columns and CRLF lines`(): Unit = runBlocking {
        val hover = hover("def café = 'hello'\r\nprintln café", Position(1, 10), EngineType.Native)

        assertEquals(Range(Position(1, 8), Position(1, 12)), hover.range)
    }

    @Test
    fun `native hover stays quiet inside comments`(): Unit = runBlocking {
        val hover = hover("class Sample {\n    // a comment inside the class\n}", Position(1, 13), EngineType.Native)

        assertEquals("", hover.contents.right.value)
    }

    @Test
    fun `native hover stays quiet on a blank line`(): Unit = runBlocking {
        val hover = hover("class Sample {\n    \n}", Position(1, 2), EngineType.Native)

        assertEquals("", hover.contents.right.value)
    }

    @Test
    fun `native hover stays quiet at the end of comments after supplementary characters`(): Unit = runBlocking {
        val source = "class Sample {\n    def icon = '😀'\n    // a comment\n}"
        val hover = hover(source, Position(2, 15), EngineType.Native)

        assertEquals("", hover.contents.right.value)
    }

    @Test
    fun `parameter type hover describes the class and parameter name keeps its own card`(): Unit = runBlocking {
        val source = "class Sample { void process(String input) {} }"
        val typeStart = source.indexOf("String")
        val typeHover = hover(source, Position(0, typeStart + 2), EngineType.Native)
        val nameHover = hover(source, Position(0, source.indexOf("input") + 2), EngineType.Native)

        assertContains(typeHover.contents.right.value, "class String")
        assertEquals(Range(Position(0, typeStart), Position(0, typeStart + "String".length)), typeHover.range)
        assertContains(nameHover.contents.right.value, "String input")
    }

    @Test
    fun `primitive parameter type hover does not invent a primitive class`(): Unit = runBlocking {
        val source = "class Sample { void process(int count) {} }"
        val hover = hover(source, Position(0, source.indexOf("int") + 1), EngineType.Native)

        assertContains(hover.contents.right.value, "Primitive Type")
        assertContains(hover.contents.right.value, "int")
        assertFalse(hover.contents.right.value.contains("class int"), hover.contents.right.value)
    }

    @Test
    fun `array parameter type token describes its component class`(): Unit = runBlocking {
        val source = "class Sample { void process(String[] input) {} }"
        val hover = hover(source, Position(0, source.indexOf("String") + 2), EngineType.Native)

        assertContains(hover.contents.right.value, "class String")
        assertFalse(hover.contents.right.value.contains("class String[]"), hover.contents.right.value)
        assertContains(hover.contents.right.value, "java.lang.String")
    }

    private suspend fun hover(source: String, position: Position, engine: EngineType? = null): Hover {
        val uri = workspace.resolve("Sample.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, source, 1) }
        val config = engine?.let { EngineConfiguration(it) } ?: EngineConfiguration()
        val compilation = GroovyCompilationService(documentProvider = documents, engineConfig = config)
        try {
            compilation.workspaceManager.initializeWorkspace(workspace)
            assertTrue(compilation.compile(uri, source).isSuccess)
            return HoverRequestHandler(compilation, documents).getHover(
                HoverParams(TextDocumentIdentifier(uri.toString()), position),
            )
        } finally {
            compilation.shutdown()
        }
    }
}
