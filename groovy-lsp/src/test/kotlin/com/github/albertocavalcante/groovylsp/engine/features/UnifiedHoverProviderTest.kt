package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnifiedHoverProviderTest {

    private val parseUnit = mockk<ParseUnit>()
    private val provider = UnifiedHoverProvider(parseUnit)
    private val textDocument = TextDocumentIdentifier("file:///test.groovy")

    @Test
    fun `getHover returns empty when no node at position`() {
        val position = Position(1, 1)
        every { parseUnit.nodeAt(position) } returns null

        val result = runBlocking {
            provider.getHover(HoverParams(textDocument, position))
        }

        // When returning Hover(emptyList(), null), it is a Left value
        assert(result.contents.isLeft)
        assertEquals(emptyList(), result.contents.left)
    }

    @Test
    fun `getHover returns formatted markdown for valid node`() {
        val position = Position(2, 5)
        val range = Range(Position(2, 0), Position(2, 10))
        val node = UnifiedNode(
            name = "myMethod",
            kind = UnifiedNodeKind.METHOD,
            type = "String",
            documentation = "Returns a string",
            range = range,
        )

        every { parseUnit.nodeAt(position) } returns node

        val result = runBlocking {
            provider.getHover(HoverParams(textDocument, position))
        }

        // When returning Hover(MarkupContent), it is a Right value
        assert(result.contents.isRight)
        val markup = result.contents.right
        assertEquals(MarkupKind.MARKDOWN, markup.kind)

        // Check content contains key parts
        val content = markup.value
        assert(content.contains("```groovy"))
        assert(content.contains("String myMethod"))
        assert(content.contains("Returns a string"))
        assert(content.contains("*(METHOD)*"))

        assertEquals(range, result.range)
    }

    @Test
    fun `getHover handles null documentation and type`() {
        val position = Position(3, 0)
        val node = UnifiedNode(
            name = "Foo",
            kind = UnifiedNodeKind.CLASS,
            type = null,
            documentation = null,
            range = null,
        )

        every { parseUnit.nodeAt(position) } returns node

        val result = runBlocking {
            provider.getHover(HoverParams(textDocument, position))
        }

        val content = (result.contents.right as MarkupContent).value
        assert(content.contains("Foo"))
        assert(!content.contains("null"))
        // We expect the kind info at the end
        assert(content.contains("*(CLASS)*"))
    }
}
