// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.indexing

import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedWorkspaceReferencesTest {
    @Test
    fun `shadow changes retire the captured view without changing its visible definitions and occurrences`() {
        val listed = URI.create("file:///listed/Example.groovy")
        val open = URI.create("file:///open/Example.groovy")
        val hidden = mutableSetOf(listed)
        val index = WorkspaceSymbolIndex(GroovySemanticDB()) { hidden }
        val id = "Example#"
        val range = Range(0, 0, 0, 7)
        val occurrence = SymbolOccurrence(id, range, OccurrenceRole.DEFINITION)
        for (uri in listOf(listed, open)) {
            val symbol = SymbolInfo(id, SymbolKind.CLASS, range, uri.toString(), null)
            index.updateDocument(uri, SemanticDocument(uri, listOf(symbol), listOf(occurrence)), uri.toString())
        }
        val before = index.captureReferences()
        assertTrue(before.isCurrent())
        hidden.clear()

        assertFalse(before.isCurrent())
        assertEquals(setOf(open), before.visibleDocuments)
        assertEquals(open.toString(), before.findSymbol(id)?.name)
        assertNull(before.getDocumentSnapshot(listed))
        assertEquals(setOf(open), before.findReferenceDocuments(id).keys)
        val after = index.captureReferences()
        assertTrue(after.isCurrent())
        assertEquals(listed.toString(), after.findSymbol(id)?.name)
        assertEquals(listOf(listed, open), after.findReferenceDocuments(id).keys.toList())
    }
}
