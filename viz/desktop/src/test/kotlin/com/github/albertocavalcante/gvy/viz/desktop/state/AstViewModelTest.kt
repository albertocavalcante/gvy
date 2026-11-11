package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AstViewModelTest {
    private val models = mutableListOf<AstViewModel>()

    private fun testModel(): AstViewModel = AstViewModel(
        Dispatchers.Unconfined,
        Dispatchers.Unconfined,
        ::parseSnapshot,
        3_600_000,
        FileEnvironment(Dispatchers.Unconfined, prefs = InMemoryPreferences()),
    ).also { models.add(it) }

    @AfterTest
    fun closeModels() = models.forEach { it.close() }

    private fun node(id: String, range: RangeDto?, vararg children: AstNodeDto) =
        CoreAstNodeDto(id, id, range, children.toList(), emptyMap())

    private fun hit(root: AstNodeDto, line: Int, column: Int): AstNodeDto? {
        val method = AstViewModel::class.java.getDeclaredMethod(
            "findSmallestNodeAt",
            AstNodeDto::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val model = testModel()
        model.updateSourceCode("abc")
        return method.invoke(model, root, line, column) as AstNodeDto?
    }

    @Test
    fun `adjacent ranges exclude the end and overlapping siblings choose the smallest`() {
        val left = node("left", RangeDto(1, 1, 1, 2))
        val right = node("right", RangeDto(1, 2, 1, 3))
        val broad = node("broad", RangeDto(1, 1, 1, 4))
        val root = node("root", null, broad, left, right)
        assertEquals(right, hit(root, 1, 2))
        assertNull(hit(left, 1, 2))
        assertNull(hit(node("empty", RangeDto(1, 2, 1, 2)), 1, 2))
    }

    @Test
    fun `editing and reparsing clear selection from the prior tree`() {
        val model = testModel()
        model.updateSourceCode("def x = 1")
        model.parseCode()
        val oldNode = assertNotNull(model.astTree)
        model.selectNode(oldNode)
        model.updateSourceCode("def y = 2")
        assertNull(model.selectedNode)
        assertNull(model.astTree)
        model.parseCode()
        assertNull(model.selectedNode)
        model.selectNode(oldNode)
        assertNull(model.selectedNode)
        model.selectNode(assertNotNull(model.astTree))
        model.updateSourceCode("")
        model.parseCode()
        assertNull(model.selectedNode)
        assertNull(model.astTree)
    }

    @Test
    fun `switching parser clears the previous node even when ids are reused`() {
        val model = testModel()
        model.updateSourceCode("def x = 1")
        model.parseCode()
        model.selectNode(assertNotNull(model.astTree))
        model.setParser(ParserType.NATIVE)
        assertNull(model.selectedNode)
    }

    @Test
    fun `native diagnostics use one based coordinates while retaining exclusive ends`() {
        val source = "def x = )"
        val native = GroovyParserFacade().parse(ParseRequest(URI("file:///temp.groovy"), source)).diagnostics
        assertTrue(native.isNotEmpty())
        val model = testModel()
        model.updateSourceCode(source)
        model.setParser(ParserType.NATIVE)
        assertEquals(native.size, model.parseErrors.size)
        native.zip(model.parseErrors).forEach { (original, displayed) ->
            assertEquals(original.range.start.line + 1, displayed.startLine)
            assertEquals(original.range.start.character + 1, displayed.startColumn)
            assertEquals(original.range.end.line + 1, displayed.endLine)
            assertEquals(original.range.end.character + 1, displayed.endColumn)
        }
    }

    @Test
    fun `failed file load clears prior tree and selection`() {
        val model = testModel()
        model.updateSourceCode("def x = 1")
        model.parseCode()
        model.selectNode(assertNotNull(model.astTree))
        model.loadFile(File("missing-groovy-source-does-not-exist.groovy"))
        assertNull(model.astTree)
        assertNull(model.selectedNode)
        assertTrue(model.parseErrors.isNotEmpty())
    }

    @Test
    fun `failed native parse does not keep the previous tree`() {
        val model = testModel()
        model.updateSourceCode("def x = 1")
        model.setParser(ParserType.NATIVE)
        assertNotNull(model.astTree)
        model.updateSourceCode("def x = )")
        model.parseCode()
        assertTrue(model.parseErrors.isNotEmpty())
        assertNull(model.astTree)
        assertNull(model.selectedNode)
    }

    @Test
    fun `caret movement changes tree highlight without requesting editor selection`() {
        val model = testModel()
        model.updateSourceCode("def x = 1")
        model.parseCode()
        model.selectNodeAt(1, 5)
        val first = assertNotNull(model.selectedNode)
        assertNull(model.sourceNavigation)
        model.selectNodeAt(1, 9)
        assertTrue(first != model.selectedNode)
        assertNull(model.sourceNavigation)
        model.selectNodeAt(1, 9)
        assertNull(model.sourceNavigation)
        val selected = assertNotNull(model.selectedNode)
        model.selectNode(selected)
        val request = assertNotNull(model.sourceNavigation)
        model.selectNodeAt(1, 5)
        assertEquals(request, model.sourceNavigation)
        model.selectNode(selected)
        assertTrue(assertNotNull(model.sourceNavigation).sequence > request.sequence)
        model.selectNodeAt(1, 10)
        assertNull(model.selectedNode)
    }

    @Test
    fun `multiline ranges use actual span length and deterministic depth ties`() {
        val model = testModel()
        model.updateSourceCode("abc\r\ndef\nxy")
        val deep = node("deep", RangeDto(1, 3, 2, 2))
        val tied = node("tied", RangeDto(1, 3, 2, 2), deep)
        val wide = node("wide", RangeDto(1, 1, 3, 2))
        val root = node("root", null, wide, tied)
        val method = AstViewModel::class.java.getDeclaredMethod(
            "findSmallestNodeAt",
            AstNodeDto::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        assertEquals(deep, method.invoke(model, root, 2, 1))
        assertEquals(wide, method.invoke(model, root, 2, 2))
    }

    @Test
    fun `core errors retain their coordinates and any partial parse result`() {
        val source = "def x = )"
        val result = GroovyParser(ParserConfiguration()).parse(source)
        val model = testModel()
        model.updateSourceCode(source)
        model.parseCode()
        assertTrue(result.result.isPresent)
        assertNotNull(model.astTree)
        val problem = result.problems.first { it.range != null }
        val range = assertNotNull(problem.range)
        val displayed = model.parseErrors.first { it.message == problem.message }
        assertEquals(range.begin.line, displayed.startLine)
        assertEquals(range.begin.column, displayed.startColumn)
        assertEquals(range.end.line, displayed.endLine)
        assertEquals(range.end.column, displayed.endColumn)
    }

    @Test
    fun `native file URI preserves spaces and non ASCII characters`() {
        val path = File("spaced name é.groovy").absoluteFile
        val uri = nativeUri(path.path)
        assertEquals(path.path, uri.path)
        assertTrue(uri.toASCIIString().contains("%20"))
        assertTrue(uri.toASCIIString().contains("%C3%A9"))
        assertEquals(URI("file:///temp.groovy"), nativeUri(null))
    }
}
