package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedSymbol
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionKind
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionService
import com.github.albertocavalcante.groovylsp.engine.api.UnifiedDefinition
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [UnifiedDefinitionProvider].
 *
 * Tests go-to-definition functionality for various scenarios.
 */
class UnifiedDefinitionProviderExtendedTest {

    @Test
    fun `getDefinition returns empty when node is null at position`() = runBlocking {
        val parseUnit = createMockParseUnit(nodeAtPosition = null)
        val service = MockDefinitionService(results = emptyList())
        val provider = UnifiedDefinitionProvider(parseUnit, service)
        val params = DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 0))

        val result = provider.getDefinition(params)

        assertTrue(result.left.isEmpty())
    }

    @Test
    fun `getDefinition returns results from service when node exists`() = runBlocking {
        val node = UnifiedNode(
            name = "myMethod",
            kind = UnifiedNodeKind.METHOD,
            type = "void",
            documentation = null,
            range = Range(Position(1, 4), Position(1, 20)),
            originalNode = null,
        )
        val targetRange = Range(Position(5, 4), Position(5, 20))
        val expectedResults = listOf(
            UnifiedDefinition(
                uri = "file:///test.groovy",
                range = targetRange,
                selectionRange = targetRange,
                kind = DefinitionKind.SOURCE,
            ),
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val service = MockDefinitionService(results = expectedResults)
        val provider = UnifiedDefinitionProvider(parseUnit, service)
        val params = DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), Position(1, 10))

        val result = provider.getDefinition(params)

        assertEquals(1, result.right.size)
        assertEquals("file:///test.groovy", result.right[0].targetUri)
    }

    @Test
    fun `getDefinition handles multiple definition results`() = runBlocking {
        val node = UnifiedNode(
            name = "overloaded",
            kind = UnifiedNodeKind.METHOD,
            type = "void",
            documentation = null,
            range = Range(Position(1, 4), Position(1, 20)),
            originalNode = null,
        )
        val range1 = Range(Position(5, 4), Position(5, 20))
        val range2 = Range(Position(10, 4), Position(10, 20))
        val expectedResults = listOf(
            UnifiedDefinition(
                uri = "file:///first.groovy",
                range = range1,
                selectionRange = range1,
                kind = DefinitionKind.SOURCE,
            ),
            UnifiedDefinition(
                uri = "file:///second.groovy",
                range = range2,
                selectionRange = range2,
                kind = DefinitionKind.SOURCE,
            ),
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val service = MockDefinitionService(results = expectedResults)
        val provider = UnifiedDefinitionProvider(parseUnit, service)
        val params = DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), Position(1, 10))

        val result = provider.getDefinition(params)

        assertEquals(2, result.right.size)
    }

    @Test
    fun `getDefinition preserves range from service results`() = runBlocking {
        val node = UnifiedNode(
            name = "target",
            kind = UnifiedNodeKind.FIELD,
            type = "String",
            documentation = null,
            range = Range(Position(2, 4), Position(2, 15)),
            originalNode = null,
        )
        val expectedRange = Range(Position(10, 8), Position(10, 25))
        val expectedResults = listOf(
            UnifiedDefinition(
                uri = "file:///target.groovy",
                range = expectedRange,
                selectionRange = expectedRange,
                kind = DefinitionKind.SOURCE,
            ),
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val service = MockDefinitionService(results = expectedResults)
        val provider = UnifiedDefinitionProvider(parseUnit, service)
        val params = DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), Position(2, 8))

        val result = provider.getDefinition(params)

        assertEquals(1, result.right.size)
        assertEquals(10, result.right[0].targetRange.start.line)
        assertEquals(8, result.right[0].targetRange.start.character)
    }

    @Test
    fun `getDefinition works for class references`() = runBlocking {
        val node = UnifiedNode(
            name = "TargetClass",
            kind = UnifiedNodeKind.CLASS,
            type = "TargetClass",
            documentation = null,
            range = Range(Position(5, 10), Position(5, 21)),
            originalNode = null,
        )
        val targetRange = Range(Position(0, 0), Position(0, 20))
        val expectedResults = listOf(
            UnifiedDefinition(
                uri = "file:///TargetClass.groovy",
                range = targetRange,
                selectionRange = targetRange,
                kind = DefinitionKind.SOURCE,
            ),
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val service = MockDefinitionService(results = expectedResults)
        val provider = UnifiedDefinitionProvider(parseUnit, service)
        val params = DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), Position(5, 15))

        val result = provider.getDefinition(params)

        assertEquals("file:///TargetClass.groovy", result.right[0].targetUri)
    }

    @Test
    fun `getDefinition works for variable references`() = runBlocking {
        val node = UnifiedNode(
            name = "localVar",
            kind = UnifiedNodeKind.VARIABLE,
            type = "int",
            documentation = null,
            range = Range(Position(8, 12), Position(8, 20)),
            originalNode = null,
        )
        val targetRange = Range(Position(3, 8), Position(3, 20))
        val expectedResults = listOf(
            UnifiedDefinition(
                uri = "file:///test.groovy",
                range = targetRange,
                selectionRange = targetRange,
                kind = DefinitionKind.SOURCE,
            ),
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val service = MockDefinitionService(results = expectedResults)
        val provider = UnifiedDefinitionProvider(parseUnit, service)
        val params = DefinitionParams(TextDocumentIdentifier("file:///test.groovy"), Position(8, 15))

        val result = provider.getDefinition(params)

        assertEquals(1, result.right.size)
        assertEquals(3, result.right[0].targetRange.start.line)
    }

    private fun createMockParseUnit(nodeAtPosition: UnifiedNode?): ParseUnit = object : ParseUnit {
        override val uri: String = "file:///test.groovy"
        override val isSuccessful: Boolean = true
        override val diagnostics: List<Diagnostic> = emptyList()
        override fun nodeAt(position: Position): UnifiedNode? = nodeAtPosition
        override fun allSymbols(): List<UnifiedSymbol> = emptyList()
    }

    private class MockDefinitionService(private val results: List<UnifiedDefinition>) : DefinitionService {
        override suspend fun findDefinition(
            node: UnifiedNode?,
            context: ParseUnit,
            position: Position,
        ): List<UnifiedDefinition> = results
    }
}
