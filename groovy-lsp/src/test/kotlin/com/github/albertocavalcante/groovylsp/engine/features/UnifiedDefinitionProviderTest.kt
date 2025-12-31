package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionKind
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionService
import com.github.albertocavalcante.groovylsp.engine.api.UnifiedDefinition
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDefinitionProviderTest {

    private val parseUnit = mockk<ParseUnit>()
    private val definitionService = mockk<DefinitionService>()
    private val provider = UnifiedDefinitionProvider(parseUnit, definitionService)

    @Test
    fun `getDefinition returns LocationLinks when definitions found`() {
        val position = Position(1, 1)
        val range = Range(Position(0, 0), Position(0, 5))
        val unifiedNode = mockk<UnifiedNode>()

        every { parseUnit.nodeAt(position) } returns unifiedNode
        coEvery { definitionService.findDefinition(unifiedNode, parseUnit, position) } returns listOf(
            UnifiedDefinition(
                uri = "file:///def.groovy",
                range = range,
                selectionRange = range,
                kind = DefinitionKind.SOURCE,
            ),
        )

        val result = runBlocking {
            provider.getDefinition(DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), position))
        }

        assertTrue(result.isRight)
        val links = result.right
        assertEquals(1, links.size)
        assertEquals("file:///def.groovy", links[0].targetUri)
    }

    @Test
    fun `getDefinition returns empty when node at position is null`() {
        val position = Position(1, 1)
        every { parseUnit.nodeAt(position) } returns null
        coEvery { definitionService.findDefinition(null, parseUnit, position) } returns emptyList()

        val result = runBlocking {
            provider.getDefinition(DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), position))
        }

        assertTrue(result.isLeft)
        assertTrue(result.left.isEmpty())
    }

    @Test
    fun `getDefinition returns empty when no definitions found`() {
        val position = Position(1, 1)
        val unifiedNode = mockk<UnifiedNode>()

        every { parseUnit.nodeAt(position) } returns unifiedNode
        coEvery { definitionService.findDefinition(unifiedNode, parseUnit, position) } returns emptyList()

        val result = runBlocking {
            provider.getDefinition(DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), position))
        }

        assertTrue(result.isLeft)
        assertTrue(result.left.isEmpty())
    }
}
