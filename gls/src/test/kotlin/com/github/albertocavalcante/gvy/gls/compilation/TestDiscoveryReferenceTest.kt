// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.testing.DiscoverTestsParams
import com.github.albertocavalcante.gvy.gls.providers.testing.TestRequestDelegate
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestDiscoveryReferenceTest {
    @Test
    fun `cold test discovery keeps a closed peer eligible for incoming calls after didOpen`(@TempDir root: Path) {
        val target = "def target() {}"
        val caller = "def caller() { target() }"
        val source = "/* 😀 */ class Calls { $target; $caller }"
        val origin = Files.writeString(root.resolve("Calls.groovy"), source).toUri()
        val peer = Files.writeString(root.resolve("Peer.groovy"), "class Peer {}").toUri()
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            val eager = runBlocking { compilation.getWorkspaceCompiler().compileWorkspace() }
            assertTrue(eager.success, eager.errors.toString())
            val indexer = assertNotNull(compilation.captureSnapshotSource(peer).dependencies.semanticIndexer)
            assertTrue(assertNotNull(indexer.currentReferencePublication(peer)).diskConfirmed)
            assertNull(compilation.getParseResult(peer), "Eager indexing leaves the discovery parse cache cold")

            val discovery = TestRequestDelegate(scope, compilation, buildToolManagerProvider = { null })
            val suites = discovery.discoverTests(DiscoverTestsParams(root.toUri().toString())).get(10, TimeUnit.SECONDS)
            assertTrue(suites.isEmpty())
            val afterDiscovery = assertNotNull(indexer.currentReferencePublication(peer))

            documents.put(origin, source)
            compilation.onDocumentOpened(origin)
            runBlocking { compilation.compile(origin, source) }
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(scope, compilation, options)
            val name = source.indexOf("target")
            val prepared = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(origin.toString()), Position(0, name)),
            ).get(10, TimeUnit.SECONDS).single()
            assertEquals("target", prepared.name)
            assertEquals(range(source.indexOf(target), target.length), prepared.range)
            assertEquals(range(name, "target".length), prepared.selectionRange)

            val incoming = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(prepared))
                .get(10, TimeUnit.SECONDS).single()
            assertEquals("caller", incoming.from.name)
            assertEquals(origin.toString(), incoming.from.uri)
            assertEquals(range(source.indexOf(caller), caller.length), incoming.from.range)
            assertEquals(listOf(range(source.lastIndexOf("target"), "target".length)), incoming.fromRanges)
            assertTrue(afterDiscovery.diskConfirmed, "The closed peer must retain disk authority through discovery")
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun range(start: Int, length: Int) = Range(Position(0, start), Position(0, start + length))
}
