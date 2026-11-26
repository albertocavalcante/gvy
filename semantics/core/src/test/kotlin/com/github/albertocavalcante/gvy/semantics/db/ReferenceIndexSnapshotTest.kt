// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceIndexSnapshotTest {
    private val uri = URI.create("file:///Calls.groovy")
    private val id = "Calls#target()."
    private val method = SymbolInfo(id, SymbolKind.METHOD, Range(0, 0, 0, 6), "target", "Calls#")

    private fun document(line: Int) = SemanticDocument(
        uri,
        listOf(method),
        listOf(SymbolOccurrence(id, Range(line, 0, line, 6), OccurrenceRole.CALL)),
    )

    @Test
    fun `a moved call and its source retire together while the declaration version stays stable`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(1), "first")
        val before = db.snapshotReferences()
        val version = db.version
        db.updateDocument(uri, document(3), "edited")
        val after = db.snapshotReferences()

        assertEquals(version, db.version)
        assertFalse(db.isCurrentReferenceSnapshot(before))
        assertTrue(db.isCurrentReferenceSnapshot(after))
        assertSame(after, db.snapshotReferences())
        assertEquals("first", before.documents.getValue(uri).sourceText)
        assertEquals(document(1), before.documents.getValue(uri).document)
        assertEquals("edited", after.documents.getValue(uri).sourceText)
        assertEquals(document(3), after.documents.getValue(uri).document)
        assertEquals(listOf(uri), before.filesReferencing(id))
        assertEquals(listOf(uri to method), before.findDefinitions(id))
    }

    @Test
    fun `replacing removing and clearing retire even identical or empty views`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(1), "text")
        val first = db.snapshotReferences()
        db.updateDocument(uri, document(1), "text")
        assertFalse(db.isCurrentReferenceSnapshot(first))
        val replaced = db.snapshotReferences()
        db.removeDocument(uri)
        assertFalse(db.isCurrentReferenceSnapshot(replaced))
        val removed = db.snapshotReferences()
        db.clear()
        assertFalse(db.isCurrentReferenceSnapshot(removed))
        assertTrue(removed.documents.isEmpty())
        assertEquals(listOf(uri), replaced.filesReferencing(id))
    }

    @Test
    fun `captured documents and matching file lists reject a consumer mutation`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(1), "text")
        val snapshot = db.snapshotReferences()
        assertThrows<UnsupportedOperationException> {
            (snapshot.documents as MutableMap).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (snapshot.filesReferencing(id) as MutableList).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (snapshot.documents.getValue(uri).document.occurrences as MutableList).clear()
        }
        assertEquals(listOf(uri), snapshot.filesReferencing(id))
    }
}
