package com.github.albertocavalcante.groovylsp.providers.callhierarchy

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class CallHierarchyProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var callHierarchyProvider: CallHierarchyProvider

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        callHierarchyProvider = CallHierarchyProvider(compilationService)
    }

    @Test
    fun `test prepare call hierarchy on method definition`() = runTest {
        // Arrange
        val content = """
            def targetMethod() {}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Act
        val items = callHierarchyProvider.prepareCallHierarchy(
            CallHierarchyPrepareParams(
                TextDocumentIdentifier(uri.toString()),
                Position(0, 6), // on 'targetMethod'
            ),
        )

        // Assert
        assertEquals(1, items.size)
        assertEquals("targetMethod", items[0].name)
        assertEquals(SymbolKind.Method, items[0].kind)
    }

    @Test
    fun `test prepare call hierarchy on method call`() = runTest {
        // Arrange
        val content = """
            def targetMethod() {}
            targetMethod()
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Act
        val items = callHierarchyProvider.prepareCallHierarchy(
            CallHierarchyPrepareParams(
                TextDocumentIdentifier(uri.toString()),
                Position(1, 0), // on 'targetMethod' call
            ),
        )

        // Assert
        assertEquals(1, items.size)
        assertEquals("targetMethod", items[0].name)
    }

    @Test
    fun `test incoming calls - simple direct call`() = runTest {
        // Arrange
        val content = """
            def target() {}
            def caller() {
                target()
            }
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        val targetItem = createItem("target", uri, Position(0, 4))

        // Act
        val incomingCalls = callHierarchyProvider.incomingCalls(
            CallHierarchyIncomingCallsParams(targetItem),
        )

        // Assert
        assertEquals(1, incomingCalls.size)
        assertEquals("caller", incomingCalls[0].from.name)
    }

    @Test
    fun `test incoming calls - call via closures`() = runTest {
        // Arrange
        val content = """
            def target() {}
            def caller() {
                [1, 2].each {
                    target()
                }
            }
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        val targetItem = createItem("target", uri, Position(0, 4))

        // Act
        val incomingCalls = callHierarchyProvider.incomingCalls(
            CallHierarchyIncomingCallsParams(targetItem),
        )

        // Assert
        assertEquals(1, incomingCalls.size)
        assertEquals("caller", incomingCalls[0].from.name)
    }

    @Test
    fun `test outgoing calls - simple direct call`() = runTest {
        // Arrange
        val content = """
            def target() {}
            def source() {
                target()
            }
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        val sourceItem = createItem("source", uri, Position(1, 4))

        // Act
        val outgoingCalls = callHierarchyProvider.outgoingCalls(
            CallHierarchyOutgoingCallsParams(sourceItem),
        )

        // Assert
        assertEquals(1, outgoingCalls.size)
        assertEquals("target", outgoingCalls[0].to.name)
    }

    private fun createItem(name: String, uri: URI, rangeStart: Position): CallHierarchyItem =
        CallHierarchyItem().apply {
            this.name = name
            this.kind = SymbolKind.Method
            this.uri = uri.toString()
            this.range = Range(rangeStart, rangeStart)
            this.selectionRange = Range(rangeStart, rangeStart)
        }
}
