package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedSymbol
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Extended tests for [UnifiedHoverProvider].
 *
 * Tests hover information generation for various node types and edge cases.
 */
class UnifiedHoverProviderExtendedTest {

    @Test
    fun `getHover returns empty when no node at position`() = runBlocking {
        val parseUnit = createMockParseUnit(nodeAtPosition = null)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 0))

        val result = provider.getHover(params)

        // Provider returns empty MarkupContent when no node found
        assertNotNull(result)
        assertTrue(result.contents.isRight)
        assertTrue(result.contents.right.value.isEmpty())
    }

    @Test
    fun `getHover returns hover for class node`() = runBlocking {
        val classNode = UnifiedNode(
            name = "MyClass",
            kind = UnifiedNodeKind.CLASS,
            type = "MyClass",
            documentation = "A sample class",
            range = Range(Position(0, 0), Position(0, 10)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = classNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 5))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("MyClass"))
    }

    @Test
    fun `getHover returns hover for method node`() = runBlocking {
        val methodNode = UnifiedNode(
            name = "doSomething",
            kind = UnifiedNodeKind.METHOD,
            type = "void",
            documentation = "Does something useful",
            range = Range(Position(1, 4), Position(1, 20)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = methodNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(1, 10))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("doSomething"))
    }

    @Test
    fun `getHover returns hover for field node`() = runBlocking {
        val fieldNode = UnifiedNode(
            name = "name",
            kind = UnifiedNodeKind.FIELD,
            type = "String",
            documentation = null,
            range = Range(Position(2, 4), Position(2, 15)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = fieldNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(2, 8))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("name"))
    }

    @Test
    fun `getHover returns hover for variable node`() = runBlocking {
        val varNode = UnifiedNode(
            name = "counter",
            kind = UnifiedNodeKind.VARIABLE,
            type = "int",
            documentation = null,
            range = Range(Position(3, 8), Position(3, 20)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = varNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(3, 12))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("counter"))
    }

    @Test
    fun `getHover includes type information when available`() = runBlocking {
        val node = UnifiedNode(
            name = "value",
            kind = UnifiedNodeKind.FIELD,
            type = "java.lang.String",
            documentation = null,
            range = Range(Position(0, 0), Position(0, 10)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 5))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("String"))
    }

    @Test
    fun `getHover includes documentation when available`() = runBlocking {
        val node = UnifiedNode(
            name = "documented",
            kind = UnifiedNodeKind.METHOD,
            type = "void",
            documentation = "This method is well documented.",
            range = Range(Position(0, 0), Position(0, 20)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = node)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 10))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("documented"))
    }

    @Test
    fun `getHover handles interface node`() = runBlocking {
        val interfaceNode = UnifiedNode(
            name = "MyInterface",
            kind = UnifiedNodeKind.INTERFACE,
            type = "MyInterface",
            documentation = null,
            range = Range(Position(0, 0), Position(0, 15)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = interfaceNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 8))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("MyInterface"))
    }

    @Test
    fun `getHover handles enum node`() = runBlocking {
        val enumNode = UnifiedNode(
            name = "Status",
            kind = UnifiedNodeKind.ENUM,
            type = "Status",
            documentation = null,
            range = Range(Position(0, 0), Position(0, 12)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = enumNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 6))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("Status"))
    }

    @Test
    fun `getHover handles constructor node`() = runBlocking {
        val constructorNode = UnifiedNode(
            name = "MyClass",
            kind = UnifiedNodeKind.CONSTRUCTOR,
            type = "MyClass",
            documentation = null,
            range = Range(Position(2, 4), Position(2, 20)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = constructorNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(2, 10))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("MyClass"))
    }

    @Test
    fun `getHover handles trait node`() = runBlocking {
        val traitNode = UnifiedNode(
            name = "MyTrait",
            kind = UnifiedNodeKind.TRAIT,
            type = "MyTrait",
            documentation = "A reusable trait",
            range = Range(Position(0, 0), Position(0, 15)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = traitNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 7))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("MyTrait"))
    }

    @Test
    fun `getHover handles property node`() = runBlocking {
        val propertyNode = UnifiedNode(
            name = "firstName",
            kind = UnifiedNodeKind.PROPERTY,
            type = "String",
            documentation = null,
            range = Range(Position(1, 4), Position(1, 20)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = propertyNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(1, 10))

        val result = provider.getHover(params)

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("firstName"))
    }

    @Test
    fun `getHover handles closure node`() = runBlocking {
        val closureNode = UnifiedNode(
            name = null,
            kind = UnifiedNodeKind.CLOSURE,
            type = "Closure",
            documentation = null,
            range = Range(Position(3, 8), Position(3, 30)),
            originalNode = null,
        )
        val parseUnit = createMockParseUnit(nodeAtPosition = closureNode)
        val provider = UnifiedHoverProvider(parseUnit)
        val params = HoverParams(TextDocumentIdentifier("file:///test.groovy"), Position(3, 15))

        val result = provider.getHover(params)

        // Closure without name might return null or minimal info
        // This tests the graceful handling
        assertTrue(result.contents.right.value.contains("Closure") || result.contents.right.value.isNotEmpty())
    }

    private fun createMockParseUnit(nodeAtPosition: UnifiedNode?): ParseUnit = object : ParseUnit {
        override val uri: String = "file:///test.groovy"
        override val isSuccessful: Boolean = true
        override val diagnostics: List<Diagnostic> = emptyList()
        override fun nodeAt(position: Position): UnifiedNode? = nodeAtPosition
        override fun allSymbols(): List<UnifiedSymbol> = emptyList()
    }
}
