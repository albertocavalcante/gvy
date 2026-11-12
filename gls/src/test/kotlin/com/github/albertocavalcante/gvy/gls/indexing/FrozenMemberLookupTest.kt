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
                listOf(before, captured.findField("C", "value")?.type, captured.findMethods("C", "read", 0).single().type)
            }
            assertTrue(started.await(30, TimeUnit.SECONDS))
            index.updateDocument(uri, document(TypeConstants.INT))
            published.countDown()
            assertEquals(listOf(TypeConstants.STRING, TypeConstants.STRING, TypeConstants.STRING), reader.get(30, TimeUnit.SECONDS))
            assertEquals(initialVersion, captured.version)
            assertEquals(TypeConstants.INT, index.findField("C", "value")?.type)
        } finally {
            published.countDown()
            executor.shutdownNow()
        }
    }

    private fun capture(index: WorkspaceSymbolIndex): MemberLookup = index

    private fun document(type: SemanticType) = SemanticDocument(
        uri,
        listOf(
            SymbolInfo("C#", SymbolKind.CLASS, Range(0, 0, 0, 1), "C", null),
            SymbolInfo("C#value.", SymbolKind.FIELD, Range(1, 0, 1, 1), "value", "C#", type),
            SymbolInfo("C#read().", SymbolKind.METHOD, Range(2, 0, 2, 1), "read", "C#", type),
        ),
        emptyList(),
    )
}
