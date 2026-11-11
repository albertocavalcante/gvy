package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OccurrenceSnapshotTest {
    private val uri = URI.create("file:///Caller.groovy")
    private val symbol = "A#greet()."
    private val database = GroovySemanticDB()

    private fun document(start: Int): SemanticDocument = SemanticDocument(
        uri,
        emptyList(),
        listOf(SymbolOccurrence(symbol, Range(0, start, 0, start + 5), OccurrenceRole.CALL)),
    )

    @Test
    fun `a captured occurrence retains its matching text after document replacement`() {
        database.updateDocument(uri, document(4), sourceText = "old greet()")
        val before = database.findOccurrenceSnapshots(symbol).single()

        database.updateDocument(uri, document(13), sourceText = "a new prefix greet()")
        val after = database.findOccurrenceSnapshots(symbol).single()

        assertEquals("old greet()", before.sourceText)
        assertEquals(Range(0, 4, 0, 9), before.occurrence.range)
        assertEquals("a new prefix greet()", after.sourceText)
        assertEquals(Range(0, 13, 0, 18), after.occurrence.range)
    }

    @Test
    fun `deletion removes the occurrence and its source from future queries`() {
        database.updateDocument(uri, document(4), sourceText = "old greet()")
        assertEquals(1, database.findOccurrenceSnapshots(symbol).size)

        database.removeDocument(uri)

        assertTrue(database.findOccurrenceSnapshots(symbol).isEmpty())
    }

    @Test
    fun `mutating caller lists cannot leave orphaned index entries after deletion`() {
        val occurrences = document(4).occurrences.toMutableList()
        val symbols = mutableListOf(
            SymbolInfo(symbol, SymbolKind.METHOD, Range(0, 4, 0, 9), "greet", owner = null),
        )
        database.updateDocument(uri, SemanticDocument(uri, symbols, occurrences), sourceText = "old greet()")

        symbols.clear()
        occurrences.clear()
        database.removeDocument(uri)

        assertTrue(database.findAllSymbolDefinitions(symbol).isEmpty())
        assertTrue(database.findAllOccurrences(symbol).isEmpty())
        assertTrue(database.findOccurrenceSnapshots(symbol).isEmpty())
    }

    @Test
    fun `returned document lists cannot mutate the stored indexes`() {
        val document = document(4).let { it.copy(occurrences = it.occurrences.toMutableList()) }
        database.updateDocument(uri, document, sourceText = "old greet()")
        val stored = checkNotNull(database.getDocument(uri))

        assertFailsWith<UnsupportedOperationException> {
            (stored.occurrences as MutableList<SymbolOccurrence>).clear()
        }

        assertEquals(1, database.findOccurrenceSnapshots(symbol).size)
    }

    @Test
    fun `reference documents retain import bindings and text across replacement and removal`() {
        val binding = SymbolInfo("import#A", SymbolKind.IMPORT, Range(0, 0, 0, 17), "OldAlias", null)
        val old = document(4).copy(symbols = listOf(binding))
        database.updateDocument(uri, old, "old greet()")
        val origin = assertNotNull(database.getDocumentSnapshot(uri))
        val captured = database.findReferenceDocuments(symbol).getValue(uri)
        assertSame(origin.document, captured.document)

        database.updateDocument(
            uri,
            document(13).copy(symbols = listOf(binding.copy(name = "NewAlias"))),
            "a new prefix greet()",
        )
        val replacement = database.findReferenceDocuments(symbol).getValue(uri)
        database.removeDocument(uri)

        assertEquals("OldAlias", captured.document.symbols.single().name)
        assertEquals("old greet()", captured.sourceText)
        assertEquals(Range(0, 4, 0, 9), captured.document.occurrences.single().range)
        assertEquals("NewAlias", replacement.document.symbols.single().name)
        assertEquals("a new prefix greet()", replacement.sourceText)
        assertEquals(Range(0, 13, 0, 18), replacement.document.occurrences.single().range)
        assertNull(database.getDocumentSnapshot(uri))
        assertTrue(database.findReferenceDocuments(symbol).isEmpty())
    }

    @Test
    fun `metadata-only reference documents never invent source text`() {
        database.updateDocument(uri, document(4))

        assertNull(assertNotNull(database.getDocumentSnapshot(uri)).sourceText)
        assertNull(database.findReferenceDocuments(symbol).getValue(uri).sourceText)
    }
}
