package com.github.albertocavalcante.gvy.gls.providers.callhierarchy

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
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
    fun `test prepare call hierarchy on method definition`(): Unit = runTest {
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
    fun `test prepare call hierarchy on method call`(): Unit = runTest {
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
    fun `test incoming calls - simple direct call`(): Unit = runTest {
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
    fun `incoming calls preserve callers and UTF16 ranges after supplementary characters`(): Unit = runTest {
        val uri = URI.create("file:///Calls.groovy")
        for (comment in listOf("", "/* ${"😀".repeat(12)} */")) {
            val source = "class Calls { def a() {}; def actual() { $comment a() }; def wrong() {} }"
            compilationService.compile(uri, source)
            val index = TextIndex(source)
            val target = createItem("a", uri, index.positionOf(source.indexOf("a()")))

            val incoming = callHierarchyProvider.incomingCalls(CallHierarchyIncomingCallsParams(target))

            assertEquals(listOf("actual"), incoming.map { it.from.name })
            val callOffset = source.lastIndexOf("a()")
            assertEquals(
                listOf(Range(index.positionOf(callOffset), index.positionOf(callOffset + 1))),
                incoming.single().fromRanges,
            )
            assertEquals(
                Range(
                    index.positionOf(source.indexOf("def actual")),
                    index.positionOf(source.indexOf("; def wrong")),
                ),
                incoming.single().from.range,
            )
        }
    }

    @Test
    fun `test incoming calls - call via closures`(): Unit = runTest {
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
    fun `test outgoing calls - static method and constructor`(): Unit = runTest {
        // Arrange
        val content = """
            class Other {
                static void staticTarget() {}
                Other() {}
            }
            def source() {
                Other.staticTarget()
                new Other()
            }
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        val sourceItem = createItem("source", uri, Position(4, 4))

        // Act
        val outgoingCalls = callHierarchyProvider.outgoingCalls(
            CallHierarchyOutgoingCallsParams(sourceItem),
        )

        // Assert
        assertEquals(2, outgoingCalls.size) { "Should find staticTarget and Other constructor" }
        val names = outgoingCalls.map { it.to.name }.toSet()
        assertEquals(setOf("staticTarget", "Other"), names)
    }

    @Test
    fun `test outgoing calls - multiple calls to same method`(): Unit = runTest {
        // Arrange
        val content = """
            def target() {}
            def source() {
                target()
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
        assertEquals(1, outgoingCalls.size) { "Outgoing calls should be grouped by target callee" }
        assertEquals("target", outgoingCalls[0].to.name)
        assertEquals(2, outgoingCalls[0].fromRanges.size) { "Should have two fromRanges for the two calls" }
    }

    @Test
    fun `test outgoing calls - simple direct call`(): Unit = runTest {
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
