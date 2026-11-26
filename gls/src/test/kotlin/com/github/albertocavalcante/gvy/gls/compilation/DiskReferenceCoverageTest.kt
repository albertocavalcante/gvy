// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.nativeapi.ParseMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiskReferenceCoverageTest {
    @Test
    fun `an on-demand compile from disk confirms the source of a closed non-origin`(@TempDir root: Path): Unit =
        runBlocking {
            val origin = root.resolve("Origin.groovy").toUri()
            val other = root.resolve("Other.groovy")
            Files.writeString(other, "class Other {}")
            val source = "class Origin {}"
            val documents = DocumentProvider().apply { put(origin, source) }
            val compilation = GroovyCompilationService(documentProvider = documents)
            try {
                compilation.compile(origin, source)
                assertNotNull(compilation.ensureCompiled(other.toUri()))
                val answer = compilation.referenceReadiness(origin).query { it.index.visibleDocuments }
                assertEquals(setOf(origin, other.toUri()), assertIs<SnapshotQuery.Answer<*>>(answer).value)
            } finally {
                compilation.shutdown()
            }
        }

    @Test
    fun `a closed non-origin needs evidence its indexed source followed the disk`(@TempDir root: Path): Unit =
        runBlocking {
            val origin = root.resolve("Origin.groovy").toUri()
            val other = root.resolve("Other.groovy")
            val otherSource = "class Other { void caller(Origin origin) { origin.target() } }"
            Files.writeString(other, otherSource)
            val source = "class Origin { void target() {} }"
            val documents = DocumentProvider().apply { put(origin, source) }
            val compilation = GroovyCompilationService(documentProvider = documents)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                compilation.compile(origin, source)
                // Supplied text alone proves nothing about the current disk file.
                compilation.compile(other.toUri(), otherSource)
                val answer = compilation.referenceReadiness(origin).query { it.index.visibleDocuments }
                assertIs<SnapshotQuery.Failure>(answer)
                val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
                val supplied = assertNotNull(index.getDocument(other.toUri()))
                val dependencies = compilation.captureSnapshotSource(other.toUri()).dependencies
                val indexer = assertNotNull(dependencies.semanticIndexer)
                val previous = assertNotNull(indexer.currentReferencePublication(other.toUri()))
                assertSame(supplied, previous.document)
                assertFalse(previous.diskConfirmed)
                assertTrue(assertNotNull(previous.producer).complete)
                assertEquals(Phases.CANONICALIZATION, previous.phase)
                compilation.refreshClosedReferences(other, ParseMode.WORKSPACE)
                val qualified = compilation.referenceReadiness(origin).query { facts ->
                    assertEquals(ParseMode.WORKSPACE, facts.modeFor(other.toUri()))
                    facts.index.getDocumentSnapshot(other.toUri())?.document
                }
                assertSame(supplied, assertIs<SnapshotQuery.Answer<*>>(qualified).value)

                val service = GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
                val item = service.prepareCallHierarchy(
                    CallHierarchyPrepareParams(
                        TextDocumentIdentifier(origin.toString()),
                        Position(0, source.indexOf("target")),
                    ),
                ).get(10, TimeUnit.SECONDS).single()
                val incoming = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                    .get(10, TimeUnit.SECONDS).single()
                assertEquals(other.toUri().toString(), incoming.from.uri)
                assertEquals("caller", incoming.from.name)
                val start = otherSource.indexOf("target")
                val range = Range(Position(0, start), Position(0, start + "target".length))
                assertEquals(listOf(range), incoming.fromRanges)
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }

    @Test
    fun `only a current disk refresh can restore retired coverage`(@TempDir root: Path): Unit = runBlocking {
        val origin = root.resolve("Origin.groovy").toUri()
        val other = root.resolve("Other.groovy")
        val diskSource = "class Other {}"
        Files.writeString(other, diskSource)
        val source = "class Origin {}"
        val documents = DocumentProvider().apply { put(origin, source) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.compile(origin, source)
            val workspace = compilation.getWorkspaceCompiler()
            workspace.compileWorkspace()
            suspend fun query(): SnapshotQuery<Set<URI>> {
                val readiness = compilation.referenceReadiness(origin)
                readiness.prepareOpenSources(compilation)
                return readiness.query { it.index.visibleDocuments }
            }
            assertIs<SnapshotQuery.Answer<*>>(query())

            val uri = other.toUri()
            val oldRead = assertNotNull(compilation.onClosedSourceChanged(uri, keepIfUnchanged = false))
            compilation.onClosedSourceChanged(uri, keepIfUnchanged = false)
            assertNull(workspace.confirmDiskSource(oldRead, diskSource))
            assertIs<SnapshotQuery.Failure>(query())

            compilation.refreshSemanticFromDisk(listOf(uri))
            assertIs<SnapshotQuery.Answer<*>>(query())
            Files.delete(other)
            assertIs<SnapshotQuery.Failure>(query())
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `coverage reads only the unconfirmed closed origin body`(@TempDir root: Path): Unit = runBlocking {
        val paths = List(16) { root.resolve("Source$it.groovy") }
        val origin = paths.first().toUri()
        val index = WorkspaceSymbolIndex(GroovySemanticDB())
        val stored = paths.associate { path ->
            val content = "// ${path.fileName}"
            Files.writeString(path, content)
            val uri = path.toUri()
            val document = SemanticDocument(uri, emptyList(), emptyList())
            uri to index.storeDocument(uri, document, content, ImplementationFacts.empty)
        }
        val capture = mockk<SnapshotSource> {
            every { uri } returns origin
            every { documents } returns emptyMap()
            every { workspace } returns mockk { every { sources } returns paths }
            every { isCurrent() } returns true
        }
        val producer = mockk<ReferenceProducer> {
            every { complete } returns true
            every { isCurrent(capture, any(), any(), any()) } returns true
        }
        val reads = mutableListOf<URI>()
        val readiness = ReferenceReadiness(
            capture,
            index,
            currentPublication = { uri ->
                ReferencePublication(stored.getValue(uri), Phases.SEMANTIC_ANALYSIS, uri != origin, producer)
            },
            selectSources = { _, model -> model.sources },
            readOrigin = { uri ->
                reads.add(uri)
                Files.readString(Path.of(uri))
            },
        )
        val answer = readiness.query { it.index.visibleDocuments.size }
        assertEquals(paths.size, assertIs<SnapshotQuery.Answer<*>>(answer).value)
        assertEquals(listOf(origin), reads)
    }
}
