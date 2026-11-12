// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.indexing

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrozenMemberLookupTest {
    private val uri = URI.create("file:///workspace/C.groovy")

    @Test
    fun `captured lookup remains consistent across concurrent workspace publication`() {
        val db = GroovySemanticDB()
        val index = WorkspaceSymbolIndex(db)
        index.updateDocument(uri, document(TypeConstants.STRING))
        val captured = capture(index)
        val initialVersion = captured.version
        val started = CountDownLatch(1)
        val published = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<List<SemanticType?>> {
                val before = captured.findField("C", "value")?.type
                started.countDown()
                check(published.await(30, TimeUnit.SECONDS))
                listOf(
                    before,
                    captured.findField("C", "value")?.type,
                    captured.findMethods("C", "read", 0).single().type,
                )
            }
            assertTrue(started.await(30, TimeUnit.SECONDS))
            index.updateDocument(uri, document(TypeConstants.INT))
            published.countDown()
            assertEquals(
                listOf(TypeConstants.STRING, TypeConstants.STRING, TypeConstants.STRING),
                reader.get(30, TimeUnit.SECONDS),
            )
            assertEquals(initialVersion, captured.version)
            assertEquals(TypeConstants.INT, index.findField("C", "value")?.type)
        } finally {
            published.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `capture preserves ordered duplicates and a copied shadow set`() {
        val db = GroovySemanticDB()
        val second = URI.create("file:///workspace/other/C.groovy")
        val hidden = mutableSetOf(uri)
        val index = WorkspaceSymbolIndex(db) { hidden }
        index.updateDocument(uri, document(TypeConstants.STRING))
        index.updateDocument(second, document(TypeConstants.INT, second))
        val captured = index.captureMembers(hidden)
        assertEquals(index.findField("C", "value"), captured.findField("C", "value"))
        assertEquals(index.findMethods("C", "read", 0), captured.findMethods("C", "read", 0))
        assertEquals(index.getAllMembers("C", false), captured.getAllMembers("C", false))

        hidden.clear()
        index.removeDocument(second)
        assertEquals(TypeConstants.INT, captured.findField("C", "value")?.type)
        assertEquals(TypeConstants.STRING, index.findField("C", "value")?.type)
    }

    @Test
    fun `captured member view reports database retirement without discarding old answers`() {
        val db = GroovySemanticDB()
        val index = WorkspaceSymbolIndex(db)
        index.updateDocument(uri, document(TypeConstants.STRING))
        val captured = index.captureMemberSnapshot(emptySet())
        assertTrue(captured.isDatabaseCurrent())
        val version = captured.lookup.version
        val moved = document(TypeConstants.STRING).copy(
            symbols = document(TypeConstants.STRING).symbols.map { it.copy(range = Range(9, 0, 9, 1)) },
        )
        index.updateDocument(uri, moved)
        assertEquals(version, db.version)
        assertEquals(false, captured.isDatabaseCurrent())
        assertEquals(TypeConstants.STRING, captured.lookup.findField("C", "value")?.type)
        assertTrue(index.captureMemberSnapshot(emptySet()).isDatabaseCurrent())
    }

    @Test
    fun `retained member view does not own its database`() {
        val queue = ReferenceQueue<GroovySemanticDB>()
        fun captureWithoutOwner(): Pair<CapturedWorkspaceMembers, WeakReference<GroovySemanticDB>> {
            val db = GroovySemanticDB()
            val index = WorkspaceSymbolIndex(db)
            index.updateDocument(uri, document(TypeConstants.STRING))
            return index.captureMemberSnapshot(emptySet()) to WeakReference(db, queue)
        }
        val (captured, reference) = captureWithoutOwner()
        repeat(50) {
            if (reference.get() == null) return@repeat
            System.gc()
            queue.remove(100)
        }
        assertEquals(null, reference.get())
        assertEquals(false, captured.isDatabaseCurrent())
        assertEquals(TypeConstants.STRING, captured.lookup.findField("C", "value")?.type)
    }

    @Test
    fun `capture freezes nested type arguments and does not expose mutable lists`() {
        val arguments = mutableListOf<SemanticType>(TypeConstants.STRING)
        val index = WorkspaceSymbolIndex(GroovySemanticDB())
        index.updateDocument(uri, document(SemanticType.Known("List", arguments)))
        // Published data must no longer alias the caller before a session captures it.
        arguments[0] = TypeConstants.INT
        val captured = index.captureMembers(emptySet())
        val type = captured.findField("C", "value")?.type as SemanticType.Known
        assertEquals(listOf(TypeConstants.STRING), type.typeArgs)
        assertThrows<UnsupportedOperationException> { (type.typeArgs as MutableList<SemanticType>).clear() }
    }

    @Test
    fun `captures remain stable while caller mutates its old type arguments`() {
        val arguments = mutableListOf<SemanticType>(TypeConstants.STRING)
        val index = WorkspaceSymbolIndex(GroovySemanticDB())
        index.updateDocument(uri, document(SemanticType.Known("List", arguments)))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                started.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                repeat(100) { arguments[0] = if (it % 2 == 0) TypeConstants.INT else TypeConstants.STRING }
            }
            assertTrue(started.await(30, TimeUnit.SECONDS))
            release.countDown()
            repeat(100) {
                val type = index.captureMembers(emptySet()).findField("C", "value")?.type as SemanticType.Known
                assertEquals(listOf(TypeConstants.STRING), type.typeArgs)
            }
            writer.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun capture(index: WorkspaceSymbolIndex): MemberLookup = index.captureMembers(emptySet())

    private fun document(type: SemanticType, documentUri: URI = uri) = SemanticDocument(
        documentUri,
        listOf(
            SymbolInfo("C#", SymbolKind.CLASS, Range(0, 0, 0, 1), "C", null),
            SymbolInfo("C#value.", SymbolKind.FIELD, Range(1, 0, 1, 1), "value", "C#", type),
            SymbolInfo("C#read().", SymbolKind.METHOD, Range(2, 0, 2, 1), "read", "C#", type),
        ),
        emptyList(),
    )
}
