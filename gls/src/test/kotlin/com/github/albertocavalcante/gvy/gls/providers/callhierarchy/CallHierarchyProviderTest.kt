// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
    fun `prepared items retain UTF16 ranges and resolve incoming callers`(): Unit = runTest {
        val uri = URI.create("file:///PreparedCalls.groovy")
        for (prefix in listOf("", "/* ${"😀".repeat(12)} */ ")) {
            val target = "def target() {}"
            val caller = "def caller() { target() }"
            val source = "${prefix}class Calls { $target; $caller }"
            compilationService.compile(uri, source)
            val index = TextIndex(source)
            val prepared = callHierarchyProvider.prepareCallHierarchy(
                CallHierarchyPrepareParams(
                    TextDocumentIdentifier(uri.toString()),
                    index.positionOf(source.indexOf("target")),
                ),
            ).single()

            assertEquals("target", prepared.name)
            val offset = source.indexOf(target)
            val expectedRange = Range(index.positionOf(offset), index.positionOf(offset + target.length))
            assertEquals(expectedRange, prepared.range)
            assertEquals(expectedRange, prepared.selectionRange)
            val incoming = callHierarchyProvider.incomingCalls(CallHierarchyIncomingCallsParams(prepared))
            assertEquals(listOf("caller"), incoming.map { it.from.name })
            val callOffset = source.lastIndexOf("target")
            assertEquals(
                listOf(Range(index.positionOf(callOffset), index.positionOf(callOffset + "target".length))),
                incoming.single().fromRanges,
            )
        }
    }

    @Test
    fun `incoming caller items can be used for outgoing calls after supplementary characters`(): Unit = runTest {
        val uri = URI.create("file:///RoundTripCalls.groovy")
        for (prefix in listOf("", "/* ${"😀".repeat(40)} */ ")) {
            val source = "class Calls { def target() {}; ${prefix}def caller() { target() }; " +
                "def decoy() { other() }; def other() {} }"
            compilationService.compile(uri, source)
            val index = TextIndex(source)
            val target = createItem("target", uri, index.positionOf(source.indexOf("target")))
            val incoming = callHierarchyProvider.incomingCalls(CallHierarchyIncomingCallsParams(target)).single()
            assertEquals("caller", incoming.from.name)

            val outgoing = callHierarchyProvider.outgoingCalls(CallHierarchyOutgoingCallsParams(incoming.from))

            assertEquals(listOf("target"), outgoing.map { it.to.name })
            val offset = source.lastIndexOf("target")
            assertEquals(
                listOf(Range(index.positionOf(offset), index.positionOf(offset + "target()".length))),
                outgoing.single().fromRanges,
            )
        }
    }

    @Test
    fun `outgoing callee items retain UTF16 ranges after supplementary characters`(): Unit = runTest {
        val uri = URI.create("file:///CalleeCalls.groovy")
        for (prefix in listOf("", "/* ${"😀".repeat(12)} */ ")) {
            val target = "def target() {}"
            val source = "class Calls { def caller() { target() }; $prefix$target }"
            compilationService.compile(uri, source)
            val index = TextIndex(source)
            val caller = createItem("caller", uri, index.positionOf(source.indexOf("caller")))

            val outgoing = callHierarchyProvider.outgoingCalls(CallHierarchyOutgoingCallsParams(caller)).single()

            assertEquals("target", outgoing.to.name)
            val offset = source.indexOf(target)
            val expectedRange = Range(index.positionOf(offset), index.positionOf(offset + target.length))
            assertEquals(expectedRange, outgoing.to.range)
            assertEquals(expectedRange, outgoing.to.selectionRange)
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
